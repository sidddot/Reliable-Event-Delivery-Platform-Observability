package com.eventplatform.api.dto;

import java.util.UUID;

public record EventIngestResponse(
        UUID id,
        String idempotencyKey,
        String eventType,
        String partitionKey,
        int deliveriesCount,
        boolean isDuplicate
) {
}
