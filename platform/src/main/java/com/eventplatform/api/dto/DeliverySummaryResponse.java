package com.eventplatform.api.dto;

import com.eventplatform.delivery.DeliveryEntity;
import com.eventplatform.delivery.DeliveryStatus;

import java.time.Instant;
import java.util.UUID;

public record DeliverySummaryResponse(
        UUID id,
        UUID subscriptionId,
        DeliveryStatus status,
        int attemptCount,
        Instant nextAttemptAt,
        String lastError,
        Instant deadAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static DeliverySummaryResponse fromEntity(DeliveryEntity entity) {
        return new DeliverySummaryResponse(
                entity.getId(),
                entity.getSubscriptionId(),
                entity.getStatus(),
                entity.getAttemptCount(),
                entity.getNextAttemptAt(),
                entity.getLastError(),
                entity.getDeadAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
