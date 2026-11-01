package com.student.studentservice.util;

import com.student.studentservice.service.Roles;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.access.expiration}")
    private Long accessExpiration;

    @Value("${jwt.refresh.expiration}")
    private Long refreshExpiration;

    // generate signing key from secret
    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    // generate access token
    // passwordChangeRequired = true for users still on their temp password;
    // JwtAuthFilter then only lets them reach the password-change/logout/refresh endpoints.
    public String generateAccessToken(String email, Roles role, boolean passwordChangeRequired) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role.name());
        claims.put("type", "ACCESS");
        claims.put("pwdChangeRequired", passwordChangeRequired);

        return Jwts.builder()
                .claims(claims)
                .subject(email)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessExpiration))
                .signWith(getSigningKey())
                .compact();
    }

    // generate refresh token
    public String generateRefreshToken(String email) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("type", "REFRESH");

        return Jwts.builder()
                .claims(claims)
                // Unique id: JWT timestamps have 1-second resolution, so without this two logins
                // in the same second produce identical tokens and violate the unique DB constraint.
                .id(UUID.randomUUID().toString())
                .subject(email)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshExpiration))
                .signWith(getSigningKey())
                .compact();
    }

    // extract email from token
    public String extractEmail(String token) {
        return extractClaims(token).getSubject();
    }

    // extract role from token
    public String extractRole(String token) {
        return extractClaims(token).get("role", String.class);
    }

    // extract token type (ACCESS or REFRESH)
    public String extractTokenType(String token) {
        return extractClaims(token).get("type", String.class);
    }

    // extract whether the user must change their temp password before doing anything else
    public boolean isPasswordChangeRequired(String token) {
        return Boolean.TRUE.equals(extractClaims(token).get("pwdChangeRequired", Boolean.class));
    }

    // validate token. Signature and expiry are enforced by the parser itself
    // (an expired token throws ExpiredJwtException), so only the subject needs checking here.
    public boolean validateToken(String token, String email) {
        try {
            return extractEmail(token).equals(email);
        } catch (Exception e) {
            log.error("Token validation failed: {}", e.getMessage());
            return false;
        }
    }

    // extract all claims from token
    private Claims extractClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
