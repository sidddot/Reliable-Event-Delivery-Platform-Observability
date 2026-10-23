package com.eventplatform.resilience;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;

/**
 * Simple token‑bucket rate limiter per subscription using a Redis Lua script.
 * The Lua script returns 1 when a token was consumed, 0 otherwise.
 */
@Component
public class RedisRateLimiter {

    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<Long> script;
    private final int defaultRate;
    private final int defaultBurst;

    public RedisRateLimiter(RedisTemplate<String, String> redisTemplate,
                            @Value("${app.rate-limiter.default-rate-per-sec:100}") int defaultRate,
                            @Value("${app.rate-limiter.default-burst:100}") int defaultBurst) {
        this.redisTemplate = redisTemplate;
        this.defaultRate = defaultRate;
        this.defaultBurst = defaultBurst;
        this.script = new DefaultRedisScript<>();
        this.script.setResultType(Long.class);
        this.script.setLocation(new ClassPathResource("lua/token_bucket.lua"));
    }

    /**
     * Attempt to consume a token for the given subscription.
     * @param subscriptionId the subscription UUID (as a string)
     * @return true if a token was available, false otherwise
     */
    public boolean tryConsume(String subscriptionId) {
        // key: rate:{subscriptionId}
        String key = "rate:" + subscriptionId;
        // args: maxTokens, refillRate (tokens per sec), burst (max tokens), ttl (seconds)
        // Using defaults if not configured per‑subscription (the service can fetch per‑sub values later).
        Long result = redisTemplate.execute(script,
                Collections.singletonList(key),
                defaultBurst,            // max tokens (bucket capacity)
                defaultRate,             // refill tokens per second
                defaultBurst,            // current tokens (initially full)
                60L);                    // bucket TTL (seconds) to auto‑expire inactive keys
        return result != null && result == 1L;
    }
}
