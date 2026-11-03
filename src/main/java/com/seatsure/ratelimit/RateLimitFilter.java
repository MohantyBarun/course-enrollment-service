package com.seatsure.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Predicate;

/**
 * Per-client-IP limits: strict on the credential endpoints (slows password guessing without
 * letting anyone lock other people out), generous on the rest of the API.
 * Runs inside the security chain after CORS, so 429 responses still carry CORS headers.
 */
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    /** First matching rule wins. */
    record Rule(String name, Predicate<HttpServletRequest> matches, int limitPerMinute) {
    }

    private final RateLimiter rateLimiter;
    private final ClientIpResolver clientIp;
    private final List<Rule> rules;
    private final MeterRegistry meterRegistry;

    public RateLimitFilter(RateLimiter rateLimiter, ClientIpResolver clientIp, RateLimitProperties limits,
                           MeterRegistry meterRegistry) {
        this.rateLimiter = rateLimiter;
        this.clientIp = clientIp;
        this.meterRegistry = meterRegistry;
        this.rules = List.of(
                new Rule("login", post("/auth/login"), limits.loginPerMinute()),
                new Rule("register", post("/auth/register"), limits.registerPerMinute()),
                new Rule("demo-login", post("/auth/demo-login"), limits.demoLoginPerMinute()),
                new Rule("refresh", post("/auth/refresh"), limits.refreshPerMinute()),
                new Rule("api", request -> true, limits.apiPerMinute()));
    }

    private static Predicate<HttpServletRequest> post(String path) {
        return request -> HttpMethod.POST.matches(request.getMethod()) && path.equals(pathOf(request));
    }

    private static String pathOf(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = pathOf(request);
        // Webhooks come from the payment gateway (signed, and retried by it), not from visitors
        return HttpMethod.OPTIONS.matches(request.getMethod())
                || path.startsWith("/actuator") || path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs")
                || path.startsWith("/webhooks/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Rule rule = rules.stream().filter(r -> r.matches().test(request)).findFirst().orElseThrow();
        // Counted back from the right of X-Forwarded-For: a client can't pick its own bucket
        String client = clientIp.resolve(request);
        RateLimiter.Decision decision = rateLimiter.tryAcquire(rule.name() + ":" + client, rule.limitPerMinute(), WINDOW);

        response.setHeader("X-RateLimit-Limit", String.valueOf(decision.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }

        meterRegistry.counter("ratelimit.rejected", "rule", rule.name()).increment();
        log.warn("Rate limit '{}' exceeded by {}", rule.name(), client);
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
        response.setContentType("application/json");
        response.getWriter().write("{\"timestamp\":\"" + LocalDateTime.now() + "\",\"status\":429,"
                + "\"error\":\"Too many requests. Please try again in " + decision.retryAfterSeconds() + " seconds.\"}");
    }
}
