package com.seatsure.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single-instance fallback when Redis isn't enabled. Counts are per JVM, so with several
 * instances each one would allow the full limit; that's what RedisRateLimiter is for.
 */
public class InMemoryRateLimiter implements RateLimiter {

    private static final int PURGE_THRESHOLD = 10_000;

    private record Window(long resetAtMillis, int count) {
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryRateLimiter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Decision tryAcquire(String key, int limit, Duration window) {
        long now = clock.millis();
        if (windows.size() > PURGE_THRESHOLD) {
            windows.values().removeIf(w -> w.resetAtMillis() <= now);
        }
        // compute() is atomic per key, so concurrent requests can't both take the last slot
        Window current = windows.compute(key, (k, w) ->
                w == null || w.resetAtMillis() <= now
                        ? new Window(now + window.toMillis(), 1)
                        : new Window(w.resetAtMillis(), w.count() + 1));
        long retryAfter = Math.max(1, (current.resetAtMillis() - now + 999) / 1000);
        return new Decision(current.count() <= limit, limit, Math.max(0, limit - current.count()), retryAfter);
    }
}
