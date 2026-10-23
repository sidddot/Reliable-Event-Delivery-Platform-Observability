package com.eventplatform;

import com.eventplatform.delivery.DeliveryOutcome;
import com.eventplatform.delivery.OutcomeClassifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class OutcomeClassifierTest {

    private final OutcomeClassifier classifier = new OutcomeClassifier();

    @Test
    @DisplayName("2xx status codes must classify as SUCCESS")
    void testSuccess() {
        assertThat(classifier.classify(200, null)).isEqualTo(DeliveryOutcome.SUCCESS);
        assertThat(classifier.classify(201, null)).isEqualTo(DeliveryOutcome.SUCCESS);
        assertThat(classifier.classify(204, null)).isEqualTo(DeliveryOutcome.SUCCESS);
    }

    @Test
    @DisplayName("5xx, 408, 429 and network exceptions must classify as RETRYABLE_FAILURE")
    void testRetryableFailures() {
        // 5xx server errors
        assertThat(classifier.classify(500, null)).isEqualTo(DeliveryOutcome.RETRYABLE_FAILURE);
        assertThat(classifier.classify(502, null)).isEqualTo(DeliveryOutcome.RETRYABLE_FAILURE);
        assertThat(classifier.classify(503, null)).isEqualTo(DeliveryOutcome.RETRYABLE_FAILURE);
        assertThat(classifier.classify(504, null)).isEqualTo(DeliveryOutcome.RETRYABLE_FAILURE);

        // 408 & 429
        assertThat(classifier.classify(408, null)).isEqualTo(DeliveryOutcome.RETRYABLE_FAILURE);
        assertThat(classifier.classify(429, null)).isEqualTo(DeliveryOutcome.RETRYABLE_FAILURE);

        // Network errors
        assertThat(classifier.classify(null, new SocketTimeoutException("Read timed out")))
                .isEqualTo(DeliveryOutcome.RETRYABLE_FAILURE);
        assertThat(classifier.classify(null, new IOException("Connection refused")))
                .isEqualTo(DeliveryOutcome.RETRYABLE_FAILURE);
    }

    @Test
    @DisplayName("Other 4xx status codes must classify as NON_RETRYABLE_FAILURE")
    void testNonRetryableFailures() {
        assertThat(classifier.classify(400, null)).isEqualTo(DeliveryOutcome.NON_RETRYABLE_FAILURE);
        assertThat(classifier.classify(401, null)).isEqualTo(DeliveryOutcome.NON_RETRYABLE_FAILURE);
        assertThat(classifier.classify(403, null)).isEqualTo(DeliveryOutcome.NON_RETRYABLE_FAILURE);
        assertThat(classifier.classify(404, null)).isEqualTo(DeliveryOutcome.NON_RETRYABLE_FAILURE);
        assertThat(classifier.classify(422, null)).isEqualTo(DeliveryOutcome.NON_RETRYABLE_FAILURE);
    }
}
