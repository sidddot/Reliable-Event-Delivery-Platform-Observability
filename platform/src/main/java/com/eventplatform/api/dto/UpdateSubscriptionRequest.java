package com.eventplatform.api.dto;

import com.eventplatform.subscription.SubscriptionStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import java.util.List;

public record UpdateSubscriptionRequest(
        String name,

        @Pattern(regexp = "^https?://.+", message = "url must be a valid HTTP or HTTPS URL")
        String url,

        List<String> eventTypes,

        SubscriptionStatus status,

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
