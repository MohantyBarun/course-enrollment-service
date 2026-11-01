package com.seatsure.util;

import com.seatsure.service.Roles;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Short-lived access tokens (JWT). Refresh tokens are not JWTs: they're opaque random values
 * checked against the database (see TokenService), so they can be rotated and revoked.
 */
@Component
public class JwtUtil {

    private final SecretKey signingKey;
    private final long accessExpirationMillis;

    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.access.expiration}") long accessExpirationMillis) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessExpirationMillis = accessExpirationMillis;
    }

    public String generateAccessToken(Long userId, String email, Roles role) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(email)
                .claim("uid", userId)
                .claim("role", role.name())
                .issuedAt(new Date(now))
                .expiration(new Date(now + accessExpirationMillis))
                .signWith(signingKey)
                .compact();
    }

    public long accessExpirationSeconds() {
        return accessExpirationMillis / 1000;
    }

    /** Signature and expiry are enforced by the parser: an invalid or expired token throws. */
    public CurrentUser parse(String token) {
        Claims claims = Jwts.parser().verifyWith(signingKey).build().parseSignedClaims(token).getPayload();
        Number uid = claims.get("uid", Number.class);
        String role = claims.get("role", String.class);
        if (uid == null || role == null || claims.getSubject() == null) {
            throw new IllegalArgumentException("Token is missing required claims");
        }
        return new CurrentUser(uid.longValue(), claims.getSubject(), Roles.valueOf(role));
    }
}
