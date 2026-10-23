package com.eventplatform.api.dto;

import com.eventplatform.delivery.DeliveryAttemptEntity;

import java.time.Instant;
import java.util.UUID;

public record DeliveryAttemptResponse(
        UUID id,
        UUID deliveryId,
        int attemptNo,
        Instant startedAt,
        long durationMs,
        Integer httpStatus,
        String error,
        String responseSnippet,
        String workerId,
        Instant createdAt
) {
    public static DeliveryAttemptResponse fromEntity(DeliveryAttemptEntity entity) {
        return new DeliveryAttemptResponse(
                entity.getId(),
                entity.getDeliveryId(),
                entity.getAttemptNo(),
                entity.getStartedAt(),
                entity.getDurationMs(),
                entity.getHttpStatus(),
                entity.getError(),
                entity.getResponseSnippet(),
                entity.getWorkerId(),
                entity.getCreatedAt()
        );
    }
}
