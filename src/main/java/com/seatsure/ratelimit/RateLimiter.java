package com.seatsure.ratelimit;

import java.time.Duration;

/** Fixed-window request counter: at most {@code limit} hits per key per {@code window}. */
public interface RateLimiter {

    Decision tryAcquire(String key, int limit, Duration window);

    /** @param retryAfterSeconds seconds until the current window ends */
    record Decision(boolean allowed, int limit, int remaining, long retryAfterSeconds) {
    }
}
