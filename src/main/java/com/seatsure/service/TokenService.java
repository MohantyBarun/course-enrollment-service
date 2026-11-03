package com.seatsure.service;

import java.time.Clock;
import com.seatsure.dto.AuthDtos.TokenResponse;
import com.seatsure.dto.AuthDtos.UserView;
import com.seatsure.entity.RefreshToken;
import com.seatsure.entity.User;
import com.seatsure.exception.AuthenticationFailedException;
import com.seatsure.repository.RefreshTokenRepository;
import com.seatsure.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Issues access + refresh token pairs and rotates refresh tokens.
 *
 * <p>Refresh tokens are 256-bit random values; only their SHA-256 is stored. Every refresh revokes
 * the presented token and issues a new one in the same family. If a token that was already rotated
 * shows up again, two parties hold the same session (e.g. a stolen token), so the whole family is
 * revoked and both have to log in again.
 */
@Slf4j
@Service
public class TokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository refreshTokens;
    private final JwtUtil jwtUtil;
    private final Duration refreshTtl;
    private final Clock clock;

    public TokenService(RefreshTokenRepository refreshTokens, JwtUtil jwtUtil,
                        @Value("${app.auth.refresh-ttl:P7D}") Duration refreshTtl, Clock clock) {
        this.refreshTokens = refreshTokens;
        this.jwtUtil = jwtUtil;
        this.refreshTtl = refreshTtl;
        this.clock = clock;
    }

    /** A new session (login, registration, demo login): a new token family. */
    @Transactional(propagation = Propagation.MANDATORY)
    public TokenResponse issue(User user) {
        return issue(user, UUID.randomUUID());
    }

    /**
     * Exchanges a refresh token for a new pair. noRollbackFor: revoking a family on reuse must commit
     * even though the request fails.
     */
    @Transactional(noRollbackFor = AuthenticationFailedException.class)
    public TokenResponse rotate(String presentedToken) {
        Instant now = clock.instant();
        RefreshToken token = refreshTokens.findByTokenHashForUpdate(hash(presentedToken))
                .orElseThrow(() -> new AuthenticationFailedException("Invalid refresh token. Please log in again."));
        if (token.isRevoked()) {
            int revoked = refreshTokens.revokeFamily(token.getFamilyId(), now);
            log.warn("Refresh token reuse detected for user {}; revoked {} token(s) in its family",
                    token.getUser().getId(), revoked);
            throw new AuthenticationFailedException("This session has ended. Please log in again.");
        }
        if (token.getExpiresAt().isBefore(now)) {
            throw new AuthenticationFailedException("Your session has expired. Please log in again.");
        }
        User user = token.getUser();
        if (!user.isActive()) {
            throw new AuthenticationFailedException("Account is deactivated.");
        }
        token.setRevokedAt(now);
        return issue(user, token.getFamilyId());
    }

    /** Logout ends this session only (other devices stay signed in). Unknown tokens are ignored. */
    @Transactional
    public void revokeSession(String presentedToken) {
        refreshTokens.findByTokenHashForUpdate(hash(presentedToken))
                .ifPresent(t -> refreshTokens.revokeFamily(t.getFamilyId(), clock.instant()));
    }

    /** After a password change: sign out everywhere. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeAll(Long userId) {
        refreshTokens.revokeAllForUser(userId, clock.instant());
    }

    @Transactional
    @Scheduled(cron = "0 40 3 * * *", zone = "Asia/Kolkata")
    public void purgeExpired() {
        int deleted = refreshTokens.deleteExpiredBefore(clock.instant().minus(Duration.ofDays(1)));
        log.info("Purged {} expired refresh tokens", deleted);
    }

    private TokenResponse issue(User user, UUID family) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        refreshTokens.save(new RefreshToken(user, hash(refresh), family, now, now.plus(refreshTtl)));
        String access = jwtUtil.generateAccessToken(user.getId(), user.getEmail(), user.getRole());
        return new TokenResponse(access, refresh, "Bearer", jwtUtil.accessExpirationSeconds(), UserView.of(user));
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
