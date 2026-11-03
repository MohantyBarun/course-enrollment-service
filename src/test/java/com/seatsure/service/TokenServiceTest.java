package com.seatsure.service;

import java.time.Clock;
import java.time.Instant;
import com.seatsure.dto.AuthDtos.TokenResponse;
import com.seatsure.entity.RefreshToken;
import com.seatsure.entity.User;
import com.seatsure.exception.AuthenticationFailedException;
import com.seatsure.repository.RefreshTokenRepository;
import com.seatsure.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TokenServiceTest {

    @Mock private RefreshTokenRepository repository;

    private TokenService tokens;
    private User user;

    @BeforeEach
    void setUp() {
        JwtUtil jwt = new JwtUtil("unit-test-secret-that-is-definitely-longer-than-sixty-four-bytes-for-hs512", 900_000);
        tokens = new TokenService(repository, jwt, Duration.ofDays(7), Clock.systemUTC());
        user = new User("Asha", "Asha@Example.com", "hash", Roles.ROLE_CUSTOMER);
        ReflectionTestUtils.setField(user, "id", 7L);
    }

    private RefreshToken saved() {
        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("issue: only the SHA-256 of the refresh token is stored")
    void issue_storesOnlyTheHash() {
        TokenResponse response = tokens.issue(user);

        RefreshToken stored = saved();
        assertThat(stored.getTokenHash()).isEqualTo(TokenService.hash(response.refreshToken()))
                .isNotEqualTo(response.refreshToken()).hasSize(64);
        assertThat(stored.getExpiresAt()).isAfter(Instant.now().plus(Duration.ofDays(6)));
        assertThat(response.user().email()).isEqualTo("asha@example.com");
        assertThat(response.expiresIn()).isEqualTo(900);
    }

    @Test
    @DisplayName("rotate: the old token is revoked and a new one is issued in the same family")
    void rotate_revokesOldAndKeepsFamily() {
        UUID family = UUID.randomUUID();
        RefreshToken current = new RefreshToken(user, TokenService.hash("old"), family, Instant.now(),
                Instant.now().plus(Duration.ofDays(1)));
        when(repository.findByTokenHashForUpdate(TokenService.hash("old"))).thenReturn(Optional.of(current));

        TokenResponse response = tokens.rotate("old");

        assertThat(current.isRevoked()).isTrue();
        assertThat(response.refreshToken()).isNotEqualTo("old");
        assertThat(saved().getFamilyId()).isEqualTo(family);
    }

    @Test
    @DisplayName("rotate: reusing an already-rotated token revokes the whole family (stolen token)")
    void rotate_reuseRevokesFamily() {
        UUID family = UUID.randomUUID();
        RefreshToken rotated = new RefreshToken(user, TokenService.hash("old"), family, Instant.now(),
                Instant.now().plus(Duration.ofDays(1)));
        rotated.setRevokedAt(Instant.now().minus(Duration.ofMinutes(1)));
        when(repository.findByTokenHashForUpdate(any())).thenReturn(Optional.of(rotated));

        assertThatThrownBy(() -> tokens.rotate("old")).isInstanceOf(AuthenticationFailedException.class);

        verify(repository).revokeFamily(eq(family), any());
        verify(repository, never()).save(any());
    }

    @Test
    void rotate_expiredToken_isRejected() {
        RefreshToken expired = new RefreshToken(user, TokenService.hash("old"), UUID.randomUUID(),
                Instant.now().minus(Duration.ofDays(8)), Instant.now().minus(Duration.ofDays(1)));
        when(repository.findByTokenHashForUpdate(any())).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> tokens.rotate("old")).hasMessageContaining("expired");
        verify(repository, never()).save(any());
    }

    @Test
    void rotate_unknownToken_isRejected() {
        when(repository.findByTokenHashForUpdate(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tokens.rotate("nope")).isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void rotate_deactivatedUser_isRejected() {
        user.setActive(false);
        RefreshToken token = new RefreshToken(user, TokenService.hash("t"), UUID.randomUUID(), Instant.now(),
                Instant.now().plus(Duration.ofDays(1)));
        when(repository.findByTokenHashForUpdate(any())).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> tokens.rotate("t")).hasMessageContaining("deactivated");
    }
}
