package com.eventplatform.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EventWithDeliveriesResponse(
        UUID id,
        String idempotencyKey,
        String eventType,
        String partitionKey,
        JsonNode payload,
        Instant createdAt,
        List<DeliverySummaryResponse> deliveries
) {
}
