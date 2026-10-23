package com.eventplatform.api.dto;

import com.eventplatform.subscription.CircuitState;
import com.eventplatform.subscription.SubscriptionEntity;
import com.eventplatform.subscription.SubscriptionStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SubscriptionResponse(
        UUID id,
        String name,
        String url,
        String secret,
        String previousSecret,
        Instant previousSecretExpiresAt,
        List<String> eventTypes,
        SubscriptionStatus status,
        int maxConcurrency,
        int rateLimitPerSec,
        int timeoutMs,
        CircuitState circuitState,
        int consecutiveFailures,
        Instant circuitOpenUntil,
        Instant createdAt,
        Instant updatedAt
) {
    public static SubscriptionResponse fromEntity(SubscriptionEntity entity) {
        return new SubscriptionResponse(
                entity.getId(),
                entity.getName(),
                entity.getUrl(),
                entity.getSecret(),
                entity.getPreviousSecret(),
                entity.getPreviousSecretExpiresAt(),
                entity.getEventTypes(),
                entity.getStatus(),
                entity.getMaxConcurrency(),
                entity.getRateLimitPerSec(),
                entity.getTimeoutMs(),
                entity.getCircuitState(),
                entity.getConsecutiveFailures(),
                entity.getCircuitOpenUntil(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
