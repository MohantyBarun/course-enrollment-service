package com.seatsure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.seatsure.dto.CatalogDtos.MovieCard;
import com.seatsure.entity.Certificate;
import java.time.LocalDate;
import com.seatsure.ratelimit.RateLimiter;
import com.seatsure.ratelimit.RedisRateLimiter;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The Redis rate limiter's Lua script and the Redis cache serialization, against a real Redis. */
@Testcontainers(disabledWithoutDocker = true)
class RedisIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redis = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    private static RedisRateLimiter limiter() {
        return new RedisRateLimiter(redis, CircuitBreaker.ofDefaults("redis-test"), new SimpleMeterRegistry());
    }

    @Test
    void rateLimiter_countsAtomicallyAndExpiresTheWindow() {
        RedisRateLimiter limiter = limiter();
        String key = "login:10.0.0." + System.nanoTime();

        assertThat(limiter.tryAcquire(key, 3, Duration.ofMinutes(1)).allowed()).isTrue();
        assertThat(limiter.tryAcquire(key, 3, Duration.ofMinutes(1)).allowed()).isTrue();
        assertThat(limiter.tryAcquire(key, 3, Duration.ofMinutes(1)).remaining()).isZero();

        RateLimiter.Decision fourth = limiter.tryAcquire(key, 3, Duration.ofMinutes(1));
        assertThat(fourth.allowed()).isFalse();
        assertThat(fourth.retryAfterSeconds()).isBetween(1L, 60L);
        assertThat(redis.getExpire("rl:" + key)).as("window key always has a TTL").isPositive();
    }

    @Test
    void rateLimiter_failsOpenWhenRedisIsUnreachable() {
        LettuceConnectionFactory dead = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 1));
        dead.afterPropertiesSet();
        dead.start();
        try {
            RedisRateLimiter limiter = new RedisRateLimiter(new StringRedisTemplate(dead),
                    CircuitBreaker.ofDefaults("dead"), new SimpleMeterRegistry());

            assertThat(limiter.tryAcquire("k", 1, Duration.ofMinutes(1)).allowed()).isTrue();
            assertThat(limiter.tryAcquire("k", 1, Duration.ofMinutes(1)).allowed()).isTrue();
        } finally {
            dead.destroy();
        }
    }

    @Test
    void nowShowingCache_roundTripsAsTypedJson() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        CacheManager cacheManager = CacheConfig.redisCacheManager(connectionFactory, mapper);
        ((org.springframework.data.redis.cache.RedisCacheManager) cacheManager).initializeCaches();
        Cache cache = cacheManager.getCache(CacheConfig.NOW_SHOWING);

        List<MovieCard> movies = List.of(new MovieCard(1L, "Monsoon Protocol", "Hindi", Certificate.UA,
                List.of("Action", "Thriller"), 152, LocalDate.of(2026, 10, 23), null));
        cache.put(7L, movies);

        assertThat(cache.get(7L, List.class)).isEqualTo(movies);
        assertThat(redis.opsForValue().get("seatsure:nowShowing::7"))
                .as("readable JSON, no Java type metadata").startsWith("[{\"id\":1,\"title\":\"Monsoon Protocol\"");
        assertThat(redis.getExpire("seatsure:nowShowing::7")).isBetween(1L, 300L);
    }
}
