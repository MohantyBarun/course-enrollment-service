package com.seatsure.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Signs and verifies payment webhooks: {@code SeatSure-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256>}
 * over {@code "<t>.<raw body>"}. The timestamp is signed too, and old signatures are refused, so a
 * captured webhook can't be replayed later.
 */
@Component
public class WebhookSigner {

    public static final String HEADER = "SeatSure-Signature";
    static final Duration TOLERANCE = Duration.ofMinutes(5);

    private final byte[] secret;
    private final Clock clock;

    public WebhookSigner(@Value("${app.payments.webhook-secret:}") String configuredSecret,
                         @Value("${jwt.secret}") String jwtSecret, Clock clock) {
        this.secret = (configuredSecret.isBlank() ? derive(jwtSecret) : configuredSecret)
                .getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    public String sign(String body) {
        long t = clock.instant().getEpochSecond();
        return "t=" + t + ",v1=" + hmac(t + "." + body);
    }

    /** True only for a well-formed header whose signature matches and whose timestamp is recent. */
    public boolean verify(String header, String body) {
        if (header == null) {
            return false;
        }
        Long t = null;
        String v1 = null;
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if (kv[0].equals("t")) {
                try {
                    t = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (kv[0].equals("v1")) {
                v1 = kv[1];
            }
        }
        if (t == null || v1 == null) {
            return false;
        }
        Duration age = Duration.between(Instant.ofEpochSecond(t), clock.instant()).abs();
        if (age.compareTo(TOLERANCE) > 0) {
            return false;
        }
        // Constant-time comparison: no timing hints about how much of the signature matched
        return MessageDigest.isEqual(hmac(t + "." + body).getBytes(StandardCharsets.UTF_8),
                v1.getBytes(StandardCharsets.UTF_8));
    }

    private String hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failed", e);
        }
    }

    /** A separate secret derived from the JWT secret when none is configured (never the JWT key itself). */
    private static String derive(String jwtSecret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("seatsure/payment-webhooks/v1:" + jwtSecret).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
