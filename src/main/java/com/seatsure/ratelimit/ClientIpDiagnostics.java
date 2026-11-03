package com.seatsure.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What the server sees of the caller's forwarding chain, for checking {@code app.client-ip.trusted-proxies}
 * on a new host: send a request with a made-up X-Forwarded-For and see which entry is picked as
 * the client. Addresses are masked; only their kind is shown in full.
 */
public final class ClientIpDiagnostics {

    public enum Kind { PUBLIC, PRIVATE, LOOPBACK, NOT_AN_IP }

    public record Hop(String address, Kind kind, boolean client) {
    }

    public record Report(int trustedProxies, List<Hop> chain, String note) {
    }

    private static final Pattern IP_LITERAL = Pattern.compile("^[0-9a-fA-F:.]+$");

    private ClientIpDiagnostics() {
    }

    public static Report report(HttpServletRequest request, ClientIpResolver resolver) {
        List<String> chain = ClientIpResolver.chain(request);
        int client = resolver.clientIndex(chain);
        List<Hop> hops = new ArrayList<>();
        for (int i = 0; i < chain.size(); i++) {
            hops.add(new Hop(mask(chain.get(i)), kind(chain.get(i)), i == client));
        }
        return new Report(resolver.trustedProxies(), hops, "Entries left to right; the last one is the TCP peer. "
                + "Anything left of the entry your hosting proxy added was sent by the client and can be forged.");
    }

    static Kind kind(String address) {
        if (!IP_LITERAL.matcher(address).matches()) {
            return Kind.NOT_AN_IP; // never resolve names: that would be a DNS lookup per request
        }
        try {
            InetAddress ip = InetAddress.getByName(address);
            if (ip.isLoopbackAddress()) {
                return Kind.LOOPBACK;
            }
            if (ip.isSiteLocalAddress() || ip.isLinkLocalAddress() || isSharedOrUniqueLocal(ip)) {
                return Kind.PRIVATE;
            }
            return Kind.PUBLIC;
        } catch (Exception e) {
            return Kind.NOT_AN_IP;
        }
    }

    /** 100.64.0.0/10 (carrier-grade NAT, used inside cloud networks) and fc00::/7 (IPv6 unique local). */
    private static boolean isSharedOrUniqueLocal(InetAddress ip) {
        byte[] b = ip.getAddress();
        if (b.length == 4) {
            return (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64;
        }
        return (b[0] & 0xFE) == 0xFC;
    }

    /** "203.0.113.x", "2001:db8::x": enough to tell entries apart, not enough to identify anyone. */
    static String mask(String address) {
        if (!IP_LITERAL.matcher(address).matches()) {
            return "(not an IP)";
        }
        int dot = address.lastIndexOf('.');
        if (dot > 0 && !address.contains(":")) {
            return address.substring(0, dot) + ".x";
        }
        String[] groups = address.split(":");
        return groups.length >= 2 ? groups[0] + ":" + groups[1] + "::x" : "x";
    }
}
