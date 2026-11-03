package com.seatsure.config;

import com.seatsure.idempotency.IdempotencyFilter;
import com.seatsure.idempotency.IdempotencyService;
import com.seatsure.ratelimit.ClientIpResolver;
import com.seatsure.ratelimit.InMemoryRateLimiter;
import com.seatsure.ratelimit.RateLimitFilter;
import com.seatsure.ratelimit.RateLimitProperties;
import com.seatsure.ratelimit.RateLimiter;
import com.seatsure.ratelimit.RedisRateLimiter;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;

/**
 * Rate limiting and idempotency filters. They're added to the Spring Security chain by AppConfig
 * (they need to run at a specific point in it), so their servlet-container registration is disabled.
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class HttpProtectionConfig {

    @Bean
    public RateLimiter rateLimiter(@Value("${app.redis.enabled:false}") boolean redisEnabled,
                                   ObjectProvider<StringRedisTemplate> redis,
                                   CircuitBreakerRegistry circuitBreakers,
                                   MeterRegistry meterRegistry) {
        return redisEnabled
                ? new RedisRateLimiter(redis.getObject(), circuitBreakers.circuitBreaker("redis"), meterRegistry)
                : new InMemoryRateLimiter(Clock.systemUTC());
    }

    /** How many proxies in front of the app append to X-Forwarded-For (Render: see application-prod). */
    @Bean
    public ClientIpResolver clientIpResolver(@Value("${app.client-ip.trusted-proxies:0}") int trustedProxies) {
        return new ClientIpResolver(trustedProxies);
    }

    @Bean
    @ConditionalOnProperty(name = "app.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
    public RateLimitFilter rateLimitFilter(RateLimiter rateLimiter, ClientIpResolver clientIp,
                                           RateLimitProperties properties, MeterRegistry meterRegistry) {
        return new RateLimitFilter(rateLimiter, clientIp, properties, meterRegistry);
    }

    @Bean
    public IdempotencyFilter idempotencyFilter(IdempotencyService service) {
        return new IdempotencyFilter(service);
    }

    @Bean
    @ConditionalOnProperty(name = "app.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        return disabled(filter);
    }

    @Bean
    public FilterRegistrationBean<IdempotencyFilter> idempotencyFilterRegistration(IdempotencyFilter filter) {
        return disabled(filter);
    }

    private static <T extends jakarta.servlet.Filter> FilterRegistrationBean<T> disabled(T filter) {
        FilterRegistrationBean<T> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
