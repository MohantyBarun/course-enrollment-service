package com.seatsure.insights;

import com.seatsure.ratelimit.RateLimitProperties;
import java.time.Clock;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.seatsure.entity.OutboxEvent;
import com.seatsure.notification.EmailSender;
import com.seatsure.repository.OutboxEventRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.transaction.TransactionAwareCacheDecorator;
import org.springframework.data.redis.cache.CacheStatistics;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * A live snapshot for the admin "System" page, read from the same Micrometer registry that feeds
 * /actuator/prometheus, plus the cache, outbox and circuit-breaker state. Counters are per instance
 * and reset when the server restarts.
 */
@Service
public class SystemStatusService {

    public record Infrastructure(String cache, String rateLimiter, String messaging, String email,
                                 boolean tracingExport, boolean demoMode) {
    }

    public record Http(long requests, long clientErrors, long serverErrors, double meanMs, double maxMs) {
    }

    public record CacheStat(String name, long hits, long misses, Double hitRate, Long size) {
    }

    public record Outbox(long pending, long failed, long published, long relayedPublished, long relayedRetries,
                         long relayedFailed) {
    }

    public record RateLimits(Map<String, Long> rejectedByRule, Map<String, Integer> limitsPerMinute,
                             long backendErrors) {
    }

    public record Breaker(String name, String state, Double failureRate, int bufferedCalls, long notPermittedCalls) {
    }

    public record Operation(String name, long calls, long failures, double meanMs) {
    }

    public record Jvm(long uptimeSeconds, long heapUsedMb, long heapMaxMb, int threads) {
    }

    public record Snapshot(Instant generatedAt, Infrastructure infrastructure, Http http, List<CacheStat> caches,
                           Outbox outbox, RateLimits rateLimits, List<Breaker> circuitBreakers,
                           List<Operation> operations, Jvm jvm) {
    }

    /** Polling the admin pages and actuator shouldn't drown out real traffic in the numbers. */
    private static final Predicate<Timer> APP_TRAFFIC = t -> {
        String uri = t.getId().getTag("uri");
        return uri == null || !(uri.startsWith("/actuator") || uri.startsWith("/admin"));
    };

    private final MeterRegistry meters;
    private final CacheManager cacheManager;
    private final OutboxEventRepository outboxRepository;
    private final CircuitBreakerRegistry circuitBreakers;
    private final EmailSender emailSender;
    private final Clock clock;
    private final boolean redisEnabled;
    private final boolean rabbitEnabled;
    private final boolean demoMode;
    private final boolean tracingExport;
    private final Map<String, Integer> limits;

    public SystemStatusService(MeterRegistry meters, CacheManager cacheManager, OutboxEventRepository outboxRepository,
                               CircuitBreakerRegistry circuitBreakers, EmailSender emailSender,
                               @Value("${app.redis.enabled:false}") boolean redisEnabled,
                               @Value("${app.messaging.rabbit.enabled:false}") boolean rabbitEnabled,
                               @Value("${app.demo.enabled:false}") boolean demoMode,
                               @Value("${management.otlp.tracing.endpoint:}") String otlpEndpoint,
                               RateLimitProperties rateLimits, Clock clock) {
        this.meters = meters;
        this.cacheManager = cacheManager;
        this.outboxRepository = outboxRepository;
        this.circuitBreakers = circuitBreakers;
        this.emailSender = emailSender;
        this.clock = clock;
        this.redisEnabled = redisEnabled;
        this.rabbitEnabled = rabbitEnabled;
        this.demoMode = demoMode;
        this.tracingExport = !otlpEndpoint.isBlank();
        Map<String, Integer> l = new LinkedHashMap<>();
        l.put("login", rateLimits.loginPerMinute());
        l.put("register", rateLimits.registerPerMinute());
        l.put("demo-login", rateLimits.demoLoginPerMinute());
        l.put("refresh", rateLimits.refreshPerMinute());
        l.put("api", rateLimits.apiPerMinute());
        this.limits = Collections.unmodifiableMap(l);
    }

    public Snapshot snapshot() {
        return new Snapshot(clock.instant(), infrastructure(), http(), caches(), outbox(), rateLimits(), breakers(),
                List.of(operation("auth.login", "Logins"), operation("hold.create", "Seat holds")), jvm());
    }

    Infrastructure infrastructure() {
        return new Infrastructure(
                redisEnabled ? "Redis" : "Caffeine (in-process)",
                redisEnabled ? "Redis (shared across instances)" : "In-memory (single instance)",
                rabbitEnabled ? "Outbox -> RabbitMQ (confirms, retries, DLQ)" : "Outbox -> in-process consumer",
                emailSender.mode(), tracingExport, demoMode);
    }

    Http http() {
        List<Timer> timers = meters.find("http.server.requests").timers().stream().filter(APP_TRAFFIC).toList();
        long count = timers.stream().mapToLong(Timer::count).sum();
        double totalMs = timers.stream().mapToDouble(t -> t.totalTime(TimeUnit.MILLISECONDS)).sum();
        double maxMs = timers.stream().mapToDouble(t -> t.max(TimeUnit.MILLISECONDS)).max().orElse(0);
        return new Http(count, countWithOutcome(timers, "CLIENT_ERROR"), countWithOutcome(timers, "SERVER_ERROR"),
                count == 0 ? 0 : round(totalMs / count), round(maxMs));
    }

    private static long countWithOutcome(List<Timer> timers, String outcome) {
        return timers.stream().filter(t -> outcome.equals(t.getId().getTag("outcome"))).mapToLong(Timer::count).sum();
    }

    List<CacheStat> caches() {
        return cacheManager.getCacheNames().stream().sorted().map(name -> {
            Cache cache = cacheManager.getCache(name);
            if (cache instanceof TransactionAwareCacheDecorator decorator) {
                cache = decorator.getTargetCache();
            }
            if (cache instanceof CaffeineCache caffeine) {
                CacheStats stats = caffeine.getNativeCache().stats();
                return stat(name, stats.hitCount(), stats.missCount(), caffeine.getNativeCache().estimatedSize());
            }
            if (cache instanceof RedisCache redis) {
                CacheStatistics stats = redis.getStatistics();
                return stat(name, stats.getHits(), stats.getMisses(), null);
            }
            return new CacheStat(name, 0, 0, null, null);
        }).toList();
    }

    private static CacheStat stat(String name, long hits, long misses, Long size) {
        long total = hits + misses;
        return new CacheStat(name, hits, misses, total == 0 ? null : round(100.0 * hits / total), size);
    }

    Outbox outbox() {
        return new Outbox(outboxRepository.countByStatus(OutboxEvent.Status.PENDING),
                outboxRepository.countByStatus(OutboxEvent.Status.FAILED),
                outboxRepository.countByStatus(OutboxEvent.Status.PUBLISHED),
                relayed("published"), relayed("retry"), relayed("failed"));
    }

    private long relayed(String outcome) {
        return (long) meters.find("outbox.events.relayed").tag("outcome", outcome).counters().stream()
                .mapToDouble(Counter::count).sum();
    }

    RateLimits rateLimits() {
        Map<String, Long> rejected = new LinkedHashMap<>();
        limits.keySet().forEach(rule -> rejected.put(rule, 0L));
        meters.find("ratelimit.rejected").counters().forEach(c ->
                rejected.merge(c.getId().getTag("rule"), (long) c.count(), Long::sum));
        long backendErrors = (long) meters.find("ratelimit.backend.errors").counters().stream()
                .mapToDouble(Counter::count).sum();
        return new RateLimits(rejected, limits, backendErrors);
    }

    List<Breaker> breakers() {
        return circuitBreakers.getAllCircuitBreakers().stream()
                .sorted(Comparator.comparing(CircuitBreaker::getName))
                .map(cb -> {
                    CircuitBreaker.Metrics m = cb.getMetrics();
                    float rate = m.getFailureRate();
                    return new Breaker(cb.getName(), cb.getState().name(), rate < 0 ? null : round(rate),
                            m.getNumberOfBufferedCalls(), m.getNumberOfNotPermittedCalls());
                }).toList();
    }

    /** Timers from @Observed: tag error=none on success, the exception's simple name otherwise. */
    Operation operation(String meterName, String label) {
        Collection<Timer> timers = meters.find(meterName).timers();
        long calls = timers.stream().mapToLong(Timer::count).sum();
        long failures = timers.stream().filter(t -> !"none".equals(t.getId().getTag("error")))
                .mapToLong(Timer::count).sum();
        double totalMs = timers.stream().mapToDouble(t -> t.totalTime(TimeUnit.MILLISECONDS)).sum();
        return new Operation(label, calls, failures, calls == 0 ? 0 : round(totalMs / calls));
    }

    Jvm jvm() {
        Runtime rt = Runtime.getRuntime();
        return new Jvm(ManagementFactory.getRuntimeMXBean().getUptime() / 1000,
                (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024), rt.maxMemory() / (1024 * 1024),
                ManagementFactory.getThreadMXBean().getThreadCount());
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
