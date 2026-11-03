package com.seatsure.ratelimit;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Who is calling, for per-client rate limits.
 *
 * <p>Every proxy appends the address it received the request from to {@code X-Forwarded-For}, so
 * the chain reads {@code [whatever the client sent..., client, proxy 1, ...]} followed by the peer
 * that connected to us. Only the entries added by our own proxies can be trusted: the left end is
 * whatever the client chose to send. So the client is found by counting {@code trustedProxies}
 * hops back from the right, never by reading the leftmost entry.
 *
 * <p>With 0 trusted proxies (local development, tests) the TCP peer is the client.
 */
public class ClientIpResolver {

    public static final String HEADER = "X-Forwarded-For";

    private final int trustedProxies;

    public ClientIpResolver(int trustedProxies) {
        if (trustedProxies < 0) {
            throw new IllegalArgumentException("trustedProxies must be >= 0");
        }
        this.trustedProxies = trustedProxies;
    }

    public int trustedProxies() {
        return trustedProxies;
    }

    public String resolve(HttpServletRequest request) {
        List<String> chain = chain(request);
        return chain.get(clientIndex(chain));
    }

    /** Position of the client in {@link #chain}: {@code trustedProxies} hops left of the TCP peer. */
    public int clientIndex(List<String> chain) {
        return Math.max(chain.size() - 1 - trustedProxies, 0);
    }

    /**
     * Every X-Forwarded-For entry, left to right, then the TCP peer. Read from the original
     * request: Spring's ForwardedHeaderFilter (forward-headers-strategy=framework) wraps it, hides
     * the header and reports the spoofable leftmost entry as the remote address.
     */
    public static List<String> chain(HttpServletRequest request) {
        HttpServletRequest original = unwrap(request);
        List<String> chain = new ArrayList<>();
        for (String header : Collections.list(original.getHeaders(HEADER))) {
            for (String entry : header.split(",")) {
                String address = entry.trim();
                if (!address.isEmpty()) {
                    chain.add(address);
                }
            }
        }
        chain.add(original.getRemoteAddr());
        return chain;
    }

    private static HttpServletRequest unwrap(HttpServletRequest request) {
        ServletRequest current = request;
        while (current instanceof ServletRequestWrapper wrapper
                && wrapper.getRequest() instanceof HttpServletRequest inner) {
            current = inner;
        }
        return (HttpServletRequest) current;
    }
}
