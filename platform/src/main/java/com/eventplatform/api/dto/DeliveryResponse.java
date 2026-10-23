package com.eventplatform.api.dto;

import com.eventplatform.delivery.DeliveryEntity;
import com.eventplatform.delivery.DeliveryStatus;

import java.time.Instant;
import java.util.UUID;

public record DeliveryResponse(
        UUID id,
        UUID eventId,
        UUID subscriptionId,
        DeliveryStatus status,
        int attemptCount,
        Instant nextAttemptAt,
        String lockedBy,
        Instant lockedUntil,
        String lastError,
        Instant deadAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static DeliveryResponse fromEntity(DeliveryEntity entity) {
        return new DeliveryResponse(
                entity.getId(),
                entity.getEventId(),
                entity.getSubscriptionId(),
                entity.getStatus(),
                entity.getAttemptCount(),
                entity.getNextAttemptAt(),
                entity.getLockedBy(),
                entity.getLockedUntil(),
                entity.getLastError(),
                entity.getDeadAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
