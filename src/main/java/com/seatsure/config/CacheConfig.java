package com.seatsure.config;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.seatsure.dto.AdminDtos.Dashboard;
import com.seatsure.dto.CatalogDtos.MovieCard;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.cache.transaction.TransactionAwareCacheManagerProxy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Caches:
 * <ul>
 *   <li>{@value #NOW_SHOWING}: the movies showing in a city (the home page), keyed by city. Evicted
 *       when shows are scheduled or cancelled and when movies change. Seat maps are never cached.</li>
 *   <li>{@value #ADMIN_DASHBOARD}: the admin aggregates. Evicted on bookings and cancellations,
 *       and otherwise at most a minute stale.</li>
 * </ul>
 * Redis when app.redis.enabled (shared by all instances), Caffeine in-process otherwise.
 * Both are transaction-aware: evictions happen after commit, so a concurrent reader can't re-cache
 * data that is about to change. Cache errors (e.g. Redis down) are logged and the call falls
 * through to the database instead of failing the request.
 */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    public static final String NOW_SHOWING = "nowShowing";
    public static final String ADMIN_DASHBOARD = "adminDashboard";

    private static final Map<String, Duration> TTL = Map.of(
            NOW_SHOWING, Duration.ofMinutes(5),
            ADMIN_DASHBOARD, Duration.ofSeconds(60));

    @Bean
    public CacheManager cacheManager(@Value("${app.redis.enabled:false}") boolean redisEnabled,
                                     ObjectProvider<RedisConnectionFactory> redis, ObjectMapper objectMapper) {
        if (redisEnabled) {
            return redisCacheManager(redis.getObject(), objectMapper);
        }
        SimpleCacheManager caffeine = new SimpleCacheManager();
        caffeine.setCaches(TTL.entrySet().stream()
                .map(e -> new CaffeineCache(e.getKey(), Caffeine.newBuilder()
                        .expireAfterWrite(e.getValue()).maximumSize(500).recordStats().build()))
                .toList());
        caffeine.initializeCaches();
        return new TransactionAwareCacheManagerProxy(caffeine);
    }

    static CacheManager redisCacheManager(RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        // Typed JSON per cache: readable in redis-cli, and no polymorphic type info in the payload
        JavaType catalog = objectMapper.getTypeFactory().constructCollectionType(List.class, MovieCard.class);
        JavaType dashboard = objectMapper.getTypeFactory().constructType(Dashboard.class);
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .prefixCacheNameWith("seatsure:")
                .disableCachingNullValues();
        return RedisCacheManager.builder(connectionFactory)
                .withCacheConfiguration(NOW_SHOWING, defaults.entryTtl(TTL.get(NOW_SHOWING))
                        .serializeValuesWith(SerializationPair.fromSerializer(
                                new Jackson2JsonRedisSerializer<>(objectMapper, catalog))))
                .withCacheConfiguration(ADMIN_DASHBOARD, defaults.entryTtl(TTL.get(ADMIN_DASHBOARD))
                        .serializeValuesWith(SerializationPair.fromSerializer(
                                new Jackson2JsonRedisSerializer<>(objectMapper, dashboard))))
                .disableCreateOnMissingCache()
                .transactionAware()
                .enableStatistics()
                .build();
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler();
    }
}
