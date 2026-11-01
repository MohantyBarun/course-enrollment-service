package com.student.studentservice.ratelimit;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

/**
 * Shared limits across instances. INCR and PEXPIRE run as one Lua script, so the window can't be
 * left without an expiry (a crash between two separate commands would make the key permanent).
 * Fails open: if Redis is unreachable the request is allowed, because an outage of a protective
 * cache shouldn't take the whole API down. A circuit breaker skips Redis entirely while it's
 * failing, so requests don't each wait for a connection timeout. Failures are logged and counted.
 */
@Slf4j
public class RedisRateLimiter implements RateLimiter {

    private static final RedisScript<List> SCRIPT = RedisScript.of("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl < 0 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
              ttl = tonumber(ARGV[1])
            end
            return {count, ttl}
            """, List.class);

    private final StringRedisTemplate redis;
    private final CircuitBreaker circuitBreaker;
    private final Counter failures;

    public RedisRateLimiter(StringRedisTemplate redis, CircuitBreaker circuitBreaker, MeterRegistry meterRegistry) {
        this.redis = redis;
        this.circuitBreaker = circuitBreaker;
        this.failures = Counter.builder("ratelimit.backend.errors")
                .description("Rate-limit checks that failed open because Redis was unavailable")
                .register(meterRegistry);
    }

    @Override
    public Decision tryAcquire(String key, int limit, Duration window) {
        try {
            List<?> result = circuitBreaker.executeSupplier(() ->
                    redis.execute(SCRIPT, List.of("rl:" + key), String.valueOf(window.toMillis())));
            long count = ((Number) result.get(0)).longValue();
            long ttlMillis = ((Number) result.get(1)).longValue();
            return new Decision(count <= limit, limit, (int) Math.max(0, limit - count),
                    Math.max(1, (ttlMillis + 999) / 1000));
        } catch (RuntimeException e) {
            failures.increment();
            log.warn("Rate limiter unavailable, allowing request: {}", e.getMessage());
            return new Decision(true, limit, limit, 0);
        }
    }
}
