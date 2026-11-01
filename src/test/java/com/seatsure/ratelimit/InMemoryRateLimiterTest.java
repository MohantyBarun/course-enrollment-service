package com.seatsure.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRateLimiterTest {

    private static final Duration MINUTE = Duration.ofMinutes(1);

    /** A clock the test can move forward. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-11-01T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    private final TestClock clock = new TestClock();
    private final InMemoryRateLimiter limiter = new InMemoryRateLimiter(clock);

    @Test
    void allowsUpToTheLimit_thenRejects() {
        assertThat(limiter.tryAcquire("k", 3, MINUTE).remaining()).isEqualTo(2);
        assertThat(limiter.tryAcquire("k", 3, MINUTE).allowed()).isTrue();
        assertThat(limiter.tryAcquire("k", 3, MINUTE).allowed()).isTrue();

        RateLimiter.Decision fourth = limiter.tryAcquire("k", 3, MINUTE);
        assertThat(fourth.allowed()).isFalse();
        assertThat(fourth.remaining()).isZero();
        assertThat(fourth.retryAfterSeconds()).isEqualTo(60);
    }

    @Test
    void newWindow_resetsTheCount() {
        IntStream.range(0, 3).forEach(i -> limiter.tryAcquire("k", 3, MINUTE));
        clock.advance(Duration.ofSeconds(45));
        assertThat(limiter.tryAcquire("k", 3, MINUTE).retryAfterSeconds()).isEqualTo(15);

        clock.advance(Duration.ofSeconds(15));
        assertThat(limiter.tryAcquire("k", 3, MINUTE).allowed()).isTrue();
    }

    @Test
    void keysAreIndependent() {
        IntStream.range(0, 3).forEach(i -> limiter.tryAcquire("a", 3, MINUTE));

        assertThat(limiter.tryAcquire("a", 3, MINUTE).allowed()).isFalse();
        assertThat(limiter.tryAcquire("b", 3, MINUTE).allowed()).isTrue();
    }

    @Test
    void concurrentRequests_neverExceedTheLimit() throws Exception {
        AtomicInteger allowed = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            for (int i = 0; i < 1000; i++) {
                pool.submit(() -> {
                    if (limiter.tryAcquire("hot", 100, MINUTE).allowed()) {
                        allowed.incrementAndGet();
                    }
                });
            }
        }
        assertThat(allowed).hasValue(100);
    }
}
