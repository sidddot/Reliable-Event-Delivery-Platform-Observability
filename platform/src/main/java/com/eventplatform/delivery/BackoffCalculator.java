package com.eventplatform.delivery;

import com.eventplatform.config.AppProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class BackoffCalculator {

    private final int baseBackoffSeconds;
    private final int maxBackoffSeconds;
    private final int maxAttempts;

    @org.springframework.beans.factory.annotation.Autowired
    public BackoffCalculator(AppProperties appProperties) {
        this.baseBackoffSeconds = appProperties.getDelivery().getBaseBackoffSeconds();
        this.maxBackoffSeconds = appProperties.getDelivery().getMaxBackoffSeconds();
        this.maxAttempts = appProperties.getDelivery().getMaxAttempts();
    }

    public BackoffCalculator(int baseBackoffSeconds, int maxBackoffSeconds, int maxAttempts) {
        this.baseBackoffSeconds = baseBackoffSeconds;
        this.maxBackoffSeconds = maxBackoffSeconds;
        this.maxAttempts = maxAttempts;
    }

    public Duration calculateDelay(int attemptNo, String retryAfterHeader) {
        // Honor Retry-After header if provided
        if (retryAfterHeader != null && !retryAfterHeader.isBlank()) {
            try {
                long retryAfterSeconds = Long.parseLong(retryAfterHeader.trim());
                if (retryAfterSeconds >= 0) {
                    long bounded = Math.min(retryAfterSeconds, maxBackoffSeconds);
                    return Duration.ofSeconds(bounded);
                }
            } catch (NumberFormatException ignored) {
                // If it's an HTTP-date format, fallback to jitter
            }
        }

        // Full Jitter: delay = random(0, min(cap, base * 2^attempt))
        int exponent = Math.min(Math.max(attemptNo, 0), 30);
        long exponential = (long) baseBackoffSeconds * (1L << exponent);
        long ceiling = Math.min((long) maxBackoffSeconds, exponential);

        if (ceiling <= 0) {
            ceiling = 1;
        }

        long jittered = ThreadLocalRandom.current().nextLong(0, ceiling + 1);
        return Duration.ofSeconds(jittered);
    }

    public boolean isExhausted(int attemptNo) {
        return attemptNo >= maxAttempts;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public int getBaseBackoffSeconds() {
        return baseBackoffSeconds;
    }

    public int getMaxBackoffSeconds() {
        return maxBackoffSeconds;
    }
}
