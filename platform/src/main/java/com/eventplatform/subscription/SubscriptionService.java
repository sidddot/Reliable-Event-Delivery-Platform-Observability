package com.eventplatform.subscription;

import com.eventplatform.api.ResourceNotFoundException;
import com.eventplatform.api.dto.CreateSubscriptionRequest;
import com.eventplatform.api.dto.RotateSecretRequest;
import com.eventplatform.api.dto.SubscriptionResponse;
import com.eventplatform.api.dto.UpdateSubscriptionRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public SubscriptionService(SubscriptionRepository subscriptionRepository) {
        this.subscriptionRepository = subscriptionRepository;
    }

    @Transactional
    public SubscriptionResponse createSubscription(CreateSubscriptionRequest request) {
        SubscriptionEntity entity = new SubscriptionEntity();
        entity.setName(request.name());
        entity.setUrl(request.url());
        entity.setSecret(generateSecret());
        entity.setEventTypes(request.eventTypes());
        entity.setStatus(SubscriptionStatus.ACTIVE);

        if (request.maxConcurrency() != null) {
            entity.setMaxConcurrency(request.maxConcurrency());
        }
        if (request.rateLimitPerSec() != null) {
            entity.setRateLimitPerSec(request.rateLimitPerSec());
        }
        if (request.timeoutMs() != null) {
            entity.setTimeoutMs(request.timeoutMs());
        }
        entity.setCircuitState(CircuitState.CLOSED);
        entity.setConsecutiveFailures(0);

        SubscriptionEntity saved = subscriptionRepository.save(entity);
        return SubscriptionResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public List<SubscriptionResponse> getSubscriptions(SubscriptionStatus status) {
        List<SubscriptionEntity> list = (status != null)
                ? subscriptionRepository.findByStatus(status)
                : subscriptionRepository.findAll();
        return list.stream().map(SubscriptionResponse::fromEntity).toList();
    }

    @Transactional(readOnly = true)
    public SubscriptionResponse getSubscription(UUID id) {
        SubscriptionEntity entity = subscriptionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Subscription not found: " + id));
        return SubscriptionResponse.fromEntity(entity);
    }

    @Transactional
    public SubscriptionResponse updateSubscription(UUID id, UpdateSubscriptionRequest request) {
        SubscriptionEntity entity = subscriptionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Subscription not found: " + id));

        if (request.name() != null) {
            entity.setName(request.name());
        }
        if (request.url() != null) {
            entity.setUrl(request.url());
        }
        if (request.eventTypes() != null && !request.eventTypes().isEmpty()) {
            entity.setEventTypes(request.eventTypes());
        }
        if (request.status() != null) {
            entity.setStatus(request.status());
        }
        if (request.maxConcurrency() != null) {
            entity.setMaxConcurrency(request.maxConcurrency());
        }
        if (request.rateLimitPerSec() != null) {
            entity.setRateLimitPerSec(request.rateLimitPerSec());
        }
        if (request.timeoutMs() != null) {
            entity.setTimeoutMs(request.timeoutMs());
        }

        SubscriptionEntity updated = subscriptionRepository.save(entity);
        return SubscriptionResponse.fromEntity(updated);
    }

    @Transactional
    public void deleteSubscription(UUID id) {
        if (!subscriptionRepository.existsById(id)) {
            throw new ResourceNotFoundException("Subscription not found: " + id);
        }
        subscriptionRepository.deleteById(id);
    }

    @Transactional
    public SubscriptionResponse rotateSecret(UUID id, RotateSecretRequest request) {
        SubscriptionEntity entity = subscriptionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Subscription not found: " + id));

        int retentionHours = (request != null && request.retentionHours() != null)
                ? request.retentionHours()
                : 24;

        entity.setPreviousSecret(entity.getSecret());
        entity.setPreviousSecretExpiresAt(Instant.now().plus(retentionHours, ChronoUnit.HOURS));
        entity.setSecret(generateSecret());

        SubscriptionEntity saved = subscriptionRepository.save(entity);
        return SubscriptionResponse.fromEntity(saved);
    }

    private String generateSecret() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return "whsec_" + HexFormat.of().formatHex(bytes);
    }
}
