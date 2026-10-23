package com.eventplatform.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

import java.util.List;

public record CreateSubscriptionRequest(
        @NotBlank(message = "name is required")
        String name,

        @NotBlank(message = "url is required")
        @Pattern(regexp = "^https?://.+", message = "url must be a valid HTTP or HTTPS URL")
        String url,

        @NotEmpty(message = "eventTypes must contain at least one event type or '*'")
        List<String> eventTypes,

        @Min(value = 1, message = "maxConcurrency must be at least 1")
        @Max(value = 500, message = "maxConcurrency cannot exceed 500")
        Integer maxConcurrency,

        @Min(value = 1, message = "rateLimitPerSec must be at least 1")
        @Max(value = 10000, message = "rateLimitPerSec cannot exceed 10000")
        Integer rateLimitPerSec,

        @Min(value = 100, message = "timeoutMs must be at least 100ms")
        @Max(value = 60000, message = "timeoutMs cannot exceed 60000ms")
        Integer timeoutMs
) {
}
