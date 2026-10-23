package com.eventplatform.delivery;

import com.eventplatform.event.EventEntity;
import com.eventplatform.event.EventRepository;
import com.eventplatform.signing.HmacSigner;
import com.eventplatform.subscription.SubscriptionEntity;
import com.eventplatform.subscription.SubscriptionRepository;
import com.eventplatform.metrics.DeliveryMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Component
public class DeliveryExecutor {

    private static final Logger log = LoggerFactory.getLogger(DeliveryExecutor.class);
    private final DeliveryMetrics deliveryMetrics;

    private final EventRepository eventRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final DeliveryRepository deliveryRepository;
    private final DeliveryAttemptRepository deliveryAttemptRepository;
    private final HmacSigner hmacSigner;
    private final OutcomeClassifier outcomeClassifier;
    private final BackoffCalculator backoffCalculator;
    private final HttpClient httpClient;

    public DeliveryExecutor(EventRepository eventRepository,
                            SubscriptionRepository subscriptionRepository,
                            DeliveryRepository deliveryRepository,
                            DeliveryAttemptRepository deliveryAttemptRepository,
                            HmacSigner hmacSigner,
                            OutcomeClassifier outcomeClassifier,
                            BackoffCalculator backoffCalculator,
                            DeliveryMetrics deliveryMetrics) {
        this.eventRepository = eventRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.deliveryRepository = deliveryRepository;
        this.deliveryAttemptRepository = deliveryAttemptRepository;
        this.hmacSigner = hmacSigner;
        this.outcomeClassifier = outcomeClassifier;
        this.backoffCalculator = backoffCalculator;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.deliveryMetrics = deliveryMetrics;
    }

    @Transactional
    public void executeDelivery(DeliveryEntity delivery, String workerId) {
        Optional<EventEntity> eventOpt = eventRepository.findById(delivery.getEventId());
        Optional<SubscriptionEntity> subOpt = subscriptionRepository.findById(delivery.getSubscriptionId());

        if (eventOpt.isEmpty() || subOpt.isEmpty()) {
            log.error("Missing event or subscription for deliveryId={}. Marking DEAD.", delivery.getId());
            delivery.setStatus(DeliveryStatus.DEAD);
            delivery.setLastError("Missing event or subscription relation");
            delivery.setDeadAt(Instant.now());
            delivery.setLockedBy(null);
            delivery.setLockedUntil(null);
            deliveryRepository.save(delivery);
            return;
        }

        // Record event ingestion (event already stored by ingest service)
        deliveryMetrics.incEventsIngested();

        EventEntity event = eventOpt.get();
        SubscriptionEntity subscription = subOpt.get();

        int attemptNo = delivery.getAttemptCount() + 1;
        Instant startedAt = Instant.now();
        long timestamp = startedAt.getEpochSecond();
        String payload = event.getPayload();

        String signatureHeader = hmacSigner.buildSignatureHeader(
                subscription.getSecret(),
                subscription.getPreviousSecret(),
                subscription.getPreviousSecretExpiresAt(),
                event.getId().toString(),
                timestamp,
                payload
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(subscription.getUrl()))
                .timeout(Duration.ofMillis(subscription.getTimeoutMs()))
                .header("Content-Type", "application/json")
                .header(HmacSigner.WEBHOOK_ID_HEADER, event.getId().toString())
                .header(HmacSigner.TIMESTAMP_HEADER, String.valueOf(timestamp))
                .header(HmacSigner.SIGNATURE_HEADER, signatureHeader)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        Integer httpStatus = null;
        String responseSnippet = null;
        String errorMessage = null;
        String retryAfterHeader = null;
        long durationMs = 0;
        Exception caughtException = null;

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            durationMs = Duration.between(startedAt, Instant.now()).toMillis();
            httpStatus = response.statusCode();
            responseSnippet = response.body();
            retryAfterHeader = response.headers().firstValue("Retry-After").orElse(null);
        } catch (Exception ex) {
            durationMs = Duration.between(startedAt, Instant.now()).toMillis();
            caughtException = ex;
            errorMessage = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            log.warn("Delivery attempt {} failed for deliveryId={} error={}", attemptNo, delivery.getId(), errorMessage);
        }

        // Record immutable attempt log
        DeliveryAttemptEntity attempt = new DeliveryAttemptEntity(
                delivery.getId(),
                attemptNo,
                startedAt,
                durationMs,
                httpStatus,
                errorMessage,
                responseSnippet,
                workerId
        );
        deliveryAttemptRepository.save(attempt);

        delivery.setAttemptCount(attemptNo);
        DeliveryOutcome outcome = outcomeClassifier.classify(httpStatus, caughtException);

        switch (outcome) {
            case SUCCESS -> {
                delivery.setStatus(DeliveryStatus.SUCCEEDED);
                delivery.setLockedBy(null);
                delivery.setLockedUntil(null);
                delivery.setLastError(null);
                log.info("Delivery SUCCEEDED for deliveryId={} eventId={} in {}ms", delivery.getId(), event.getId(), durationMs);
                deliveryMetrics.recordDeliverySuccess(Duration.ofMillis(durationMs));
            }
            case NON_RETRYABLE_FAILURE -> {
                delivery.setStatus(DeliveryStatus.DEAD);
                delivery.setDeadAt(Instant.now());
                delivery.setLastError(errorMessage != null ? errorMessage : "Non-retryable HTTP status " + httpStatus);
                delivery.setLockedBy(null);
                delivery.setLockedUntil(null);
                log.warn("Delivery DEAD (non-retryable client error) for deliveryId={} status={}", delivery.getId(), httpStatus);
                deliveryMetrics.recordDeliveryFailure(false);
            }
            case RETRYABLE_FAILURE -> {
                boolean willRetry = !backoffCalculator.isExhausted(attemptNo);
                if (backoffCalculator.isExhausted(attemptNo)) {
                    delivery.setStatus(DeliveryStatus.DEAD);
                    delivery.setDeadAt(Instant.now());
                    delivery.setLastError("Retries exhausted (" + attemptNo + "/" + backoffCalculator.getMaxAttempts() + "): "
                            + (errorMessage != null ? errorMessage : "HTTP " + httpStatus));
                    delivery.setLockedBy(null);
                    delivery.setLockedUntil(null);
                    log.warn("Delivery DEAD (retries exhausted) for deliveryId={} attempts={}", delivery.getId(), attemptNo);
                } else {
                    Duration delay = backoffCalculator.calculateDelay(attemptNo, retryAfterHeader);
                    delivery.setStatus(DeliveryStatus.PENDING);
                    delivery.setNextAttemptAt(Instant.now().plus(delay));
                    delivery.setLastError(errorMessage != null ? errorMessage : "HTTP " + httpStatus);
                    delivery.setLockedBy(null);
                    delivery.setLockedUntil(null);
                    log.info("Delivery RETRY scheduled for deliveryId={} attempt={} delay={}s", delivery.getId(), attemptNo, delay.toSeconds());
                    deliveryMetrics.incRetried();
                    deliveryMetrics.recordRetryAttempts(attemptNo);
                }
                deliveryMetrics.recordDeliveryFailure(willRetry);
            }
        }

        deliveryRepository.save(delivery);
    }
}
