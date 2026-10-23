package com.eventplatform.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record IngestEventRequest(
        @NotBlank(message = "eventType is required")
        String eventType,

        String partitionKey,

        @NotNull(message = "payload is required")
        JsonNode payload
) {
}
