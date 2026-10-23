package com.eventplatform.delivery;

import org.springframework.stereotype.Component;

@Component
public class OutcomeClassifier {

    public DeliveryOutcome classify(Integer httpStatus, Throwable exception) {
        // Network errors, timeouts, connection refused are always retryable
        if (exception != null) {
            return DeliveryOutcome.RETRYABLE_FAILURE;
        }

        if (httpStatus == null) {
            return DeliveryOutcome.RETRYABLE_FAILURE;
        }

        // 2xx = Success
        if (httpStatus >= 200 && httpStatus < 300) {
            return DeliveryOutcome.SUCCESS;
        }

        // 408 (Request Timeout), 429 (Too Many Requests), and 5xx (Server Errors) = Retryable
        if (httpStatus == 408 || httpStatus == 429 || (httpStatus >= 500 && httpStatus < 600)) {
            return DeliveryOutcome.RETRYABLE_FAILURE;
        }

        // Other 4xx Client Errors = Non-retryable (configuration / bad request / auth error)
        if (httpStatus >= 400 && httpStatus < 500) {
            return DeliveryOutcome.NON_RETRYABLE_FAILURE;
        }

        // Default fallback for unhandled / 3xx statuses
        return DeliveryOutcome.NON_RETRYABLE_FAILURE;
    }
}
