package com.seatsure.ratelimit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientIpResolverTest {

    /** A request as it reaches the app behind one proxy (10.0.0.5) that appends what it saw. */
    private static MockHttpServletRequest viaProxy(String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login");
        request.setRemoteAddr("10.0.0.5");
        if (forwardedFor != null) {
            request.addHeader(ClientIpResolver.HEADER, forwardedFor);
        }
        return request;
    }

    @Test
    @DisplayName("behind one proxy, the entry it appended is the client, whatever the client put in front of it")
    void oneProxy_ignoresClientSuppliedEntries() {
        ClientIpResolver resolver = new ClientIpResolver(1);
        assertThat(resolver.resolve(viaProxy("198.51.100.7"))).isEqualTo("198.51.100.7");
        assertThat(resolver.resolve(viaProxy("1.2.3.4, 198.51.100.7"))).isEqualTo("198.51.100.7");
        assertThat(resolver.resolve(viaProxy("evil, 5.6.7.8 , 198.51.100.7"))).isEqualTo("198.51.100.7");
    }

    @Test
    void multipleHeaderLines_areOneChain() {
        MockHttpServletRequest request = viaProxy("1.2.3.4");
        request.addHeader(ClientIpResolver.HEADER, "198.51.100.7, 172.16.0.9");
        assertThat(new ClientIpResolver(2).resolve(request)).isEqualTo("198.51.100.7");
    }

    @Test
    @DisplayName("no proxy configured: X-Forwarded-For is ignored and the TCP peer is the client")
    void noProxy_usesThePeer() {
        assertThat(new ClientIpResolver(0).resolve(viaProxy("1.2.3.4"))).isEqualTo("10.0.0.5");
    }

    @Test
    @DisplayName("a chain shorter than the configured hops falls back to its first entry, not an error")
    void shortChain_fallsBackToTheFirstEntry() {
        assertThat(new ClientIpResolver(3).resolve(viaProxy(null))).isEqualTo("10.0.0.5");
    }

    @Test
    @DisplayName("reads the original request: a wrapper (e.g. Spring's ForwardedHeaderFilter) can't hide the header")
    void readsThroughRequestWrappers() {
        MockHttpServletRequest original = viaProxy("1.2.3.4, 198.51.100.7");
        HttpServletRequestWrapper wrapped = new HttpServletRequestWrapper(original) {
            @Override
            public String getRemoteAddr() {
                return "1.2.3.4"; // what the framework strategy reports: the spoofable leftmost entry
            }

            @Override
            public java.util.Enumeration<String> getHeaders(String name) {
                return java.util.Collections.emptyEnumeration();
            }
        };
        assertThat(new ClientIpResolver(1).resolve(wrapped)).isEqualTo("198.51.100.7");
    }

    @Test
    void negativeHops_areRejected() {
        assertThatThrownBy(() -> new ClientIpResolver(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rotating a forged X-Forwarded-For doesn't buy a fresh rate-limit budget")
    void forgedHeaders_shareTheRealClientsBudget() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(new InMemoryRateLimiter(Clock.systemUTC()), new ClientIpResolver(1),
                new RateLimitProperties(true, 2, 5, 20, 30, 300), new SimpleMeterRegistry());
        int[] statuses = new int[3];
        for (int i = 0; i < 3; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(viaProxy("203.0.113." + i + ", 198.51.100.7"), response, new MockFilterChain());
            statuses[i] = response.getStatus();
        }
        assertThat(statuses).containsExactly(200, 200, 429);
    }

    @Test
    void diagnostics_maskAddressesAndMarkTheClient() {
        ClientIpDiagnostics.Report report = ClientIpDiagnostics.report(viaProxy("1.2.3.4, 198.51.100.7"), new ClientIpResolver(1));
        assertThat(report.chain()).extracting(ClientIpDiagnostics.Hop::address)
                .containsExactly("1.2.3.x", "198.51.100.x", "10.0.0.x");
        assertThat(report.chain()).extracting(ClientIpDiagnostics.Hop::client).containsExactly(false, true, false);
        assertThat(report.chain()).extracting(ClientIpDiagnostics.Hop::kind).containsExactly(
                ClientIpDiagnostics.Kind.PUBLIC, ClientIpDiagnostics.Kind.PUBLIC, ClientIpDiagnostics.Kind.PRIVATE);
        assertThat(ClientIpDiagnostics.kind("100.64.1.2")).isEqualTo(ClientIpDiagnostics.Kind.PRIVATE);
        assertThat(ClientIpDiagnostics.kind("evil.example")).isEqualTo(ClientIpDiagnostics.Kind.NOT_AN_IP);
        assertThat(ClientIpDiagnostics.mask("2001:db8:1:2::7")).isEqualTo("2001:db8::x");
    }
}
