package com.seatsure.idempotency;

import com.seatsure.idempotency.IdempotencyService.Claim;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Makes retried POSTs safe: a client that sends the same Idempotency-Key again (after a timeout, a
 * double click or a flaky network) gets the original response instead of a second hold or payment.
 * The header is optional; requests without it behave as before.
 * <ul>
 *   <li>same key, same request, finished: the stored response is replayed (Idempotent-Replayed: true)</li>
 *   <li>same key, still running: 409</li>
 *   <li>same key, different request: 422</li>
 *   <li>5xx responses aren't stored, so the request can be retried</li>
 * </ul>
 * Runs after JwtAuthFilter, because keys are scoped to the authenticated user.
 */
@Slf4j
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private static final Pattern VALID_KEY = Pattern.compile("^[A-Za-z0-9_\\-:.]{8,100}$");
    private static final List<String> IDEMPOTENT_POST_PATHS =
            List.of("/shows/*/holds", "/holds/*/payments", "/shows/*/waitlist", "/bookings/*/cancel", "/admin/movies", "/admin/cinemas", "/admin/cinemas/*/screens",
                    "/admin/shows", "/admin/shows/*/cancel");
    private static final AntPathMatcher PATHS = new AntPathMatcher();

    private final IdempotencyService service;

    public IdempotencyFilter(IdempotencyService service) {
        this.service = service;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!HttpMethod.POST.matches(request.getMethod()) || request.getHeader(HEADER) == null) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return IDEMPOTENT_POST_PATHS.stream().noneMatch(pattern -> PATHS.match(pattern, path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            chain.doFilter(request, response); // the security rules reject it
            return;
        }
        String key = request.getHeader(HEADER);
        if (!VALID_KEY.matcher(key).matches()) {
            writeError(response, 400, HEADER + " must be 8-100 characters: letters, digits, '-', '_', ':' or '.'");
            return;
        }

        byte[] body = request.getInputStream().readAllBytes();
        String hash = sha256(request.getMethod() + " " + request.getRequestURI() + "\n", body);

        switch (service.claim(auth.getName(), key, hash)) {
            case Claim.Replay replay -> replay(response, replay);
            case Claim.InProgress ignored ->
                    writeError(response, 409, "A request with this " + HEADER + " is still being processed");
            case Claim.Mismatch ignored ->
                    writeError(response, 422, "This " + HEADER + " was already used for a different request");
            case Claim.Acquired acquired -> execute(new CachedBodyRequest(request, body), response, chain, acquired);
        }
    }

    private void execute(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
                         Claim.Acquired acquired) throws ServletException, IOException {
        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        boolean stored = false;
        try {
            chain.doFilter(request, wrapper);
            int status = wrapper.getStatus();
            if (status < 500) {
                service.complete(acquired.recordId(), status,
                        new String(wrapper.getContentAsByteArray(), StandardCharsets.UTF_8),
                        wrapper.getContentType(), wrapper.getHeader(HttpHeaders.LOCATION));
                stored = true;
            }
        } finally {
            if (!stored) {
                service.release(acquired.recordId());
            }
            wrapper.copyBodyToResponse();
        }
    }

    private static void replay(HttpServletResponse response, Claim.Replay replay) throws IOException {
        response.setStatus(replay.status());
        response.setHeader(REPLAYED_HEADER, "true");
        if (replay.location() != null) {
            response.setHeader(HttpHeaders.LOCATION, replay.location());
        }
        if (replay.contentType() != null) {
            response.setContentType(replay.contentType());
        }
        if (replay.body() != null) {
            response.getOutputStream().write(replay.body().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"timestamp\":\"" + LocalDateTime.now() + "\",\"status\":" + status
                + ",\"error\":\"" + message + "\"}");
    }

    static String sha256(String prefix, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(prefix.getBytes(StandardCharsets.UTF_8));
            digest.update(body);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Lets the body be read again downstream after it was read here for hashing. */
    static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
