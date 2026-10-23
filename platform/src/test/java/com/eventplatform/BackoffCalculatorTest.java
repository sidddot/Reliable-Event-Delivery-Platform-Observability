package com.eventplatform;

import com.eventplatform.delivery.BackoffCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BackoffCalculatorTest {

    @Test
    @DisplayName("Calculated backoff is strictly bounded within [0, min(cap, base * 2^attempt)]")
    void testBackoffJitterBounds() {
        int base = 10;
        int cap = 3600;
        int maxAttempts = 8;
        BackoffCalculator calculator = new BackoffCalculator(base, cap, maxAttempts);

        for (int attempt = 0; attempt <= 15; attempt++) {
            long expectedMax = Math.min((long) cap, (long) base * (1L << attempt));
            for (int sample = 0; sample < 50; sample++) {
                Duration delay = calculator.calculateDelay(attempt, null);
                long delaySeconds = delay.toSeconds();
                assertThat(delaySeconds).isGreaterThanOrEqualTo(0);
                assertThat(delaySeconds).isLessThanOrEqualTo(expectedMax);
            }
        }
    }

    @Test
    @DisplayName("Retry-After header value in seconds is respected, bounded by cap")
    void testRetryAfterHeaderHandling() {
        int base = 10;
        int cap = 3600;
        int maxAttempts = 8;
        BackoffCalculator calculator = new BackoffCalculator(base, cap, maxAttempts);

        // Valid Retry-After within cap
        Duration delay = calculator.calculateDelay(1, "120");
        assertThat(delay.toSeconds()).isEqualTo(120);

        // Retry-After exceeding cap must be clamped to cap
        Duration clampedDelay = calculator.calculateDelay(1, "99999");
        assertThat(clampedDelay.toSeconds()).isEqualTo(cap);

        // Malformed Retry-After falls back to jitter calculation within bounds
        Duration fallbackDelay = calculator.calculateDelay(1, "invalid-header");
        assertThat(fallbackDelay.toSeconds()).isBetween(0L, (long) base * 2);
    }

    @Test
    @DisplayName("Exhaustion check triggers accurately at maxAttempts")
    void testExhaustion() {
        BackoffCalculator calculator = new BackoffCalculator(1, 5, 3);
        assertThat(calculator.isExhausted(1)).isFalse();
        assertThat(calculator.isExhausted(2)).isFalse();
        assertThat(calculator.isExhausted(3)).isTrue();
        assertThat(calculator.isExhausted(4)).isTrue();
    }
}
