package com.seatsure.idempotency;

import com.seatsure.idempotency.IdempotencyService.Claim;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotencyFilterTest {

    private static final String KEY = "c0ffee00-1234-4abc-8def-000000000001";
    private static final String BODY = "{\"name\":\"John\"}";

    @Mock private IdempotencyService service;

    private IdempotencyFilter filter;

    @BeforeEach
    void setUp() {
        filter = new IdempotencyFilter(service);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin@college.com", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest post(String path, String key, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        if (key != null) {
            request.addHeader(IdempotencyFilter.HEADER, key);
        }
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    /** A downstream handler that reads the body and answers like the controller would. */
    private static FilterChain handler(int status, String responseBody, AtomicReference<String> bodySeen) {
        return (req, res) -> {
            bodySeen.set(new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            HttpServletResponse http = (HttpServletResponse) res;
            http.setStatus(status);
            http.setContentType("application/json");
            http.setHeader("Location", "http://localhost/holds/42");
            http.getWriter().write(responseBody);
        };
    }

    @Test
    void withoutHeader_passesThrough() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        filter.doFilter(post("/shows/7/holds", null, BODY), new MockHttpServletResponse(), handler(201, "{}", seen));

        assertThat(seen.get()).isEqualTo(BODY);
        verifyNoInteractions(service);
    }

    @Test
    void otherEndpoints_areNotCovered() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        filter.doFilter(post("/auth/login", KEY, BODY), new MockHttpServletResponse(), handler(200, "{}", seen));

        verifyNoInteractions(service);
    }

    @Test
    void firstRequest_runsAndStoresTheResponse() throws Exception {
        when(service.claim(eq("admin@college.com"), eq(KEY), anyString())).thenReturn(new Claim.Acquired(7L));
        AtomicReference<String> seen = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(post("/shows/7/holds", KEY, BODY), response, handler(201, "{\"id\":42}", seen));

        assertThat(seen.get()).as("the controller still gets the full body").isEqualTo(BODY);
        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getContentAsString()).isEqualTo("{\"id\":42}");
        verify(service).complete(7L, 201, "{\"id\":42}", "application/json", "http://localhost/holds/42");
        verify(service, never()).release(anyLong());
    }

    @Test
    void cancelPath_isCovered() throws Exception {
        when(service.claim(anyString(), eq(KEY), anyString())).thenReturn(new Claim.Acquired(8L));

        filter.doFilter(post("/bookings/SS42/cancel", KEY, ""), new MockHttpServletResponse(),
                handler(201, "{}", new AtomicReference<>()));

        verify(service).complete(eq(8L), eq(201), anyString(), any(), any());
    }

    @Test
    void serverError_releasesTheKeySoTheClientCanRetry() throws Exception {
        when(service.claim(anyString(), eq(KEY), anyString())).thenReturn(new Claim.Acquired(7L));

        filter.doFilter(post("/shows/7/holds", KEY, BODY), new MockHttpServletResponse(),
                handler(500, "{\"error\":\"boom\"}", new AtomicReference<>()));

        verify(service).release(7L);
        verify(service, never()).complete(anyLong(), anyInt(), any(), any(), any());
    }

    @Test
    void clientError_isStoredAndReplayed() throws Exception {
        when(service.claim(anyString(), eq(KEY), anyString())).thenReturn(new Claim.Acquired(7L));

        filter.doFilter(post("/shows/7/holds", KEY, BODY), new MockHttpServletResponse(),
                handler(409, "{\"error\":\"duplicate\"}", new AtomicReference<>()));

        verify(service).complete(eq(7L), eq(409), anyString(), any(), any());
    }

    @Test
    void retry_replaysTheStoredResponseWithoutRunningAgain() throws Exception {
        when(service.claim(anyString(), eq(KEY), anyString()))
                .thenReturn(new Claim.Replay(201, "{\"id\":42}", "application/json", "http://localhost/holds/42"));
        AtomicReference<String> seen = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(post("/shows/7/holds", KEY, BODY), response, handler(201, "{}", seen));

        assertThat(seen.get()).as("controller not called").isNull();
        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getContentAsString()).isEqualTo("{\"id\":42}");
        assertThat(response.getHeader(IdempotencyFilter.REPLAYED_HEADER)).isEqualTo("true");
        assertThat(response.getHeader("Location")).isEqualTo("http://localhost/holds/42");
    }

    @Test
    void concurrentDuplicate_gets409() throws Exception {
        when(service.claim(anyString(), eq(KEY), anyString())).thenReturn(new Claim.InProgress());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(post("/shows/7/holds", KEY, BODY), response, handler(201, "{}", new AtomicReference<>()));

        assertThat(response.getStatus()).isEqualTo(409);
    }

    @Test
    void reusedKeyWithDifferentBody_gets422() throws Exception {
        when(service.claim(anyString(), eq(KEY), anyString())).thenReturn(new Claim.Mismatch());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(post("/shows/7/holds", KEY, BODY), response, handler(201, "{}", new AtomicReference<>()));

        assertThat(response.getStatus()).isEqualTo(422);
    }

    @Test
    void sameRequest_hashesTheSame_differentBody_doesNot() throws Exception {
        when(service.claim(anyString(), eq(KEY), anyString())).thenReturn(new Claim.InProgress());

        filter.doFilter(post("/shows/7/holds", KEY, BODY), new MockHttpServletResponse(), (q, s) -> { });
        filter.doFilter(post("/shows/7/holds", KEY, BODY), new MockHttpServletResponse(), (q, s) -> { });
        filter.doFilter(post("/shows/7/holds", KEY, "{\"name\":\"Jane\"}"), new MockHttpServletResponse(), (q, s) -> { });

        var hashes = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(service, times(3)).claim(anyString(), eq(KEY), hashes.capture());
        assertThat(hashes.getAllValues().get(0)).isEqualTo(hashes.getAllValues().get(1))
                .isNotEqualTo(hashes.getAllValues().get(2));
    }

    @Test
    void malformedKey_gets400() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(post("/shows/7/holds", "bad key!", BODY), response, handler(201, "{}", new AtomicReference<>()));

        assertThat(response.getStatus()).isEqualTo(400);
        verifyNoInteractions(service);
    }

    @Test
    void unauthenticated_isLeftToTheSecurityRules() throws Exception {
        SecurityContextHolder.clearContext();
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(post("/shows/7/holds", KEY, BODY), new MockHttpServletResponse(), handler(403, "{}", seen));

        verifyNoInteractions(service);
    }
}
