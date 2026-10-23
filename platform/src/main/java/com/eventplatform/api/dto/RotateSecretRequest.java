package com.eventplatform.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record RotateSecretRequest(
        @Min(value = 1, message = "retentionHours must be at least 1 hour")
        @Max(value = 720, message = "retentionHours cannot exceed 720 hours (30 days)")
        Integer retentionHours
) {
}
