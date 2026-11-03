package com.seatsure.ratelimit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final RateLimitFilter filter = new RateLimitFilter(new InMemoryRateLimiter(Clock.systemUTC()),
            new ClientIpResolver(0), new RateLimitProperties(true, 2, 5, 20, 30, 5), meters);

    private MockHttpServletResponse send(String method, String path, String ip) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void login_isLimitedPerIp() throws Exception {
        assertThat(send("POST", "/auth/login", "1.1.1.1").getStatus()).isEqualTo(200);
        assertThat(send("POST", "/auth/login", "1.1.1.1").getHeader("X-RateLimit-Remaining")).isEqualTo("0");

        MockHttpServletResponse third = send("POST", "/auth/login", "1.1.1.1");
        assertThat(third.getStatus()).isEqualTo(429);
        assertThat(third.getHeader("Retry-After")).isNotBlank();
        assertThat(third.getContentAsString()).contains("\"status\":429").contains("Too many requests");
        assertThat(meters.counter("ratelimit.rejected", "rule", "login").count()).isEqualTo(1);

        assertThat(send("POST", "/auth/login", "2.2.2.2").getStatus()).as("another client").isEqualTo(200);
    }

    @Test
    void loginAttempts_dontUseUpTheGeneralApiBudget() throws Exception {
        send("POST", "/auth/login", "1.1.1.1");
        send("POST", "/auth/login", "1.1.1.1");

        assertThat(send("GET", "/cities", "1.1.1.1").getHeader("X-RateLimit-Remaining")).isEqualTo("4");
    }

    @Test
    void generalApi_hasItsOwnLimit() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(send("GET", "/cities", "3.3.3.3").getStatus()).isEqualTo(200);
        }
        assertThat(send("GET", "/movies", "3.3.3.3").getStatus()).isEqualTo(429);
    }

    @Test
    void healthChecks_docsAndPreflights_areNeverLimited() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(send("GET", "/actuator/health", "4.4.4.4").getStatus()).isEqualTo(200);
            assertThat(send("GET", "/swagger-ui/index.html", "4.4.4.4").getStatus()).isEqualTo(200);
            assertThat(send("OPTIONS", "/auth/login", "4.4.4.4").getStatus()).isEqualTo(200);
        }
    }
}
