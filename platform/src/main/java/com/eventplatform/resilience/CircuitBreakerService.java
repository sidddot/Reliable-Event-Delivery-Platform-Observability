package com.eventplatform.resilience;

import com.eventplatform.subscription.CircuitState;
import com.eventplatform.subscription.SubscriptionRepository;
import com.eventplatform.metrics.DeliveryMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.Duration;
import java.util.UUID;

/**
 * Service that manages per‑subscription circuit‑breaker state stored in the {@code subscriptions} table.
 *
 * The circuit has three states (CLOSED, OPEN, HALF_OPEN). When a subscription experiences {@code maxFailures}
 * consecutive failures, it moves to OPEN for {@code openDuration} seconds. After that a single probe delivery is
 * allowed (HALF_OPEN). If the probe succeeds the circuit closes, otherwise it re‑opens.
 */
@Service
public class CircuitBreakerService {

    private final SubscriptionRepository subscriptionRepository;
    private final int maxFailures;
    private final Duration openDuration;
    private final DeliveryMetrics deliveryMetrics;

    public CircuitBreakerService(
            SubscriptionRepository subscriptionRepository,
            @Value("${app.circuitbreaker.max-failures:5}") int maxFailures,
            @Value("${app.circuitbreaker.open-duration-seconds:30}") int openDurationSeconds,
            DeliveryMetrics deliveryMetrics) {
        this.subscriptionRepository = subscriptionRepository;
        this.maxFailures = maxFailures;
        this.openDuration = Duration.ofSeconds(openDurationSeconds);
        this.deliveryMetrics = deliveryMetrics;
    }

    @Transactional
    public void recordSuccess(UUID subscriptionId) {
        subscriptionRepository.findById(subscriptionId).ifPresent(sub -> {
            sub.setConsecutiveFailures(0);
            sub.setCircuitState(CircuitState.CLOSED);
            sub.setCircuitOpenUntil(null);
            subscriptionRepository.save(sub);
            // metric for closing circuit
            deliveryMetrics.circuitClosed();
        });
    }

    @Transactional
    public void recordFailure(UUID subscriptionId) {
        subscriptionRepository.findById(subscriptionId).ifPresent(sub -> {
            int failures = sub.getConsecutiveFailures() + 1;
            sub.setConsecutiveFailures(failures);
            if (sub.getCircuitState() != CircuitState.OPEN && failures >= maxFailures) {
                sub.setCircuitState(CircuitState.OPEN);
                sub.setCircuitOpenUntil(Instant.now().plus(openDuration));
                // metric for opening circuit
                deliveryMetrics.circuitOpened();
            }
            subscriptionRepository.save(sub);
        });
    }

    /**
     * Returns true if the subscription is allowed to be claimed for delivery.
     */
    @Transactional(readOnly = true)
    public boolean isClosed(UUID subscriptionId) {
        return subscriptionRepository.findById(subscriptionId)
                .map(sub -> {
                    if (sub.getCircuitState() == CircuitState.CLOSED) {
                        return true;
                    }
                    if (sub.getCircuitState() == CircuitState.OPEN) {
                        // check if open duration elapsed → move to HALF_OPEN
                        if (sub.getCircuitOpenUntil() != null && Instant.now().isAfter(sub.getCircuitOpenUntil())) {
                            sub.setCircuitState(CircuitState.HALF_OPEN);
                            subscriptionRepository.save(sub);
                            // metric for half‑open transition
                            deliveryMetrics.circuitHalfOpened();
                            return true; // allow probe
                        }
                        return false;
                    }
                    // HALF_OPEN – allow a single delivery (probe)
                    return true;
                })
                .orElse(false);
    }
}
