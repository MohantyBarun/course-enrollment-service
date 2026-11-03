package com.seatsure.service;

import java.time.ZoneId;
import java.time.Clock;
import java.time.Instant;
import com.seatsure.dto.AuthDtos.ChangePasswordRequest;
import com.seatsure.dto.AuthDtos.LoginRequest;
import com.seatsure.dto.AuthDtos.RegisterRequest;
import com.seatsure.entity.LoginSecurity;
import com.seatsure.entity.User;
import com.seatsure.exception.AccountLockedException;
import com.seatsure.exception.AuthenticationFailedException;
import com.seatsure.exception.ConflictException;
import com.seatsure.outbox.OutboxEvents;
import com.seatsure.outbox.OutboxService;
import com.seatsure.repository.LoginSecurityRepository;
import com.seatsure.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

    @Mock private UserRepository users;
    @Mock private LoginSecurityRepository loginSecurity;
    @Mock private TokenService tokens;
    @Mock private OutboxService outbox;

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private AuthService auth;
    private User user;
    private LoginSecurity security;

    @BeforeEach
    void setUp() {
        auth = new AuthService(users, loginSecurity,
                new LoginThrottle(loginSecurity, Clock.systemUTC(), ZoneId.of("Asia/Kolkata")), encoder, tokens, outbox);
        user = new User("Asha", "asha@x.com", encoder.encode("Right#123"), Roles.ROLE_CUSTOMER);
        ReflectionTestUtils.setField(user, "id", 7L);
        security = new LoginSecurity(user);
        when(users.findByEmail("asha@x.com")).thenReturn(Optional.of(user));
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(loginSecurity.findByIdForUpdate(7L)).thenReturn(Optional.of(security));
    }

    @Test
    void login_success_resetsFailuresAndIssuesTokens() {
        ReflectionTestUtils.setField(security, "wrongPasswordCount", 3);

        auth.login(new LoginRequest("ASHA@x.com ", "Right#123"));

        assertThat(security.getWrongPasswordCount()).isZero();
        assertThat(security.getLastLoginAt()).isNotNull();
        verify(tokens).issue(user);
    }

    @Test
    @DisplayName("an unknown email gets the same answer as a wrong password (no account enumeration)")
    void login_unknownEmail_isIndistinguishable() {
        assertThatThrownBy(() -> auth.login(new LoginRequest("nobody@x.com", "whatever")))
                .isInstanceOf(AuthenticationFailedException.class).hasMessage(AuthService.INVALID_CREDENTIALS);
        assertThatThrownBy(() -> auth.login(new LoginRequest("asha@x.com", "wrong")))
                .isInstanceOf(AuthenticationFailedException.class).hasMessage(AuthService.INVALID_CREDENTIALS);
    }

    @Test
    void login_fifthWrongPassword_blocksFor30Minutes() {
        ReflectionTestUtils.setField(security, "wrongPasswordCount", 4);

        assertThatThrownBy(() -> auth.login(new LoginRequest("asha@x.com", "wrong")))
                .isInstanceOf(AccountLockedException.class);
        assertThat(security.getAccountStatus()).isEqualTo(AccountStatus.BLOCKED);
        assertThat(security.getBlockExpiryAt()).isAfter(Instant.now().plus(Duration.ofMinutes(29)));
        verify(tokens, never()).issue(any());
    }

    @Test
    void login_whileBlocked_isRefusedEvenWithTheRightPassword() {
        ReflectionTestUtils.setField(security, "accountStatus", AccountStatus.BLOCKED);
        ReflectionTestUtils.setField(security, "blockExpiryAt", Instant.now().plus(Duration.ofMinutes(10)));

        assertThatThrownBy(() -> auth.login(new LoginRequest("asha@x.com", "Right#123")))
                .isInstanceOf(AccountLockedException.class);
    }

    @Test
    @DisplayName("after a block expires the count is kept: the next wrong password blocks for twice as long")
    void login_afterBlockExpires_blocksEscalate() {
        ReflectionTestUtils.setField(security, "accountStatus", AccountStatus.BLOCKED);
        ReflectionTestUtils.setField(security, "blockExpiryAt", Instant.now().minus(Duration.ofMinutes(1)));
        ReflectionTestUtils.setField(security, "lastFailedAt", Instant.now().minus(Duration.ofMinutes(31)));
        ReflectionTestUtils.setField(security, "wrongPasswordCount", 5);

        assertThatThrownBy(() -> auth.login(new LoginRequest("asha@x.com", "wrong")))
                .isInstanceOf(AccountLockedException.class).hasMessageContaining("1 hour");
        assertThat(security.getWrongPasswordCount()).isEqualTo(6);
        assertThat(security.getBlockExpiryAt()).isAfter(Instant.now().plus(Duration.ofMinutes(59)));
    }

    @Test
    @DisplayName("failed logins never block an account for good, so nobody can lock its owner out permanently")
    void login_manyWrongPasswords_neverBlockPermanently() {
        ReflectionTestUtils.setField(security, "wrongPasswordCount", 40);

        assertThatThrownBy(() -> auth.login(new LoginRequest("asha@x.com", "wrong")))
                .isInstanceOf(AccountLockedException.class).hasMessageContaining("24 hours");
        assertThat(security.getPermanentBlock()).isFalse();
        assertThat(security.getAccountStatus()).isEqualTo(AccountStatus.BLOCKED);
        assertThat(security.getBlockExpiryAt()).isBefore(Instant.now().plus(Duration.ofHours(24)).plus(Duration.ofMinutes(1)));
    }

    @Test
    @DisplayName("a day-long block doesn't wipe its own failures when it ends: the next wrong password blocks again")
    void login_afterADayLongBlock_stillEscalates() {
        ReflectionTestUtils.setField(security, "accountStatus", AccountStatus.BLOCKED);
        ReflectionTestUtils.setField(security, "blockExpiryAt", Instant.now().minus(Duration.ofMinutes(1)));
        ReflectionTestUtils.setField(security, "lastFailedAt", Instant.now().minus(Duration.ofHours(25)));
        ReflectionTestUtils.setField(security, "wrongPasswordCount", 11);

        assertThatThrownBy(() -> auth.login(new LoginRequest("asha@x.com", "wrong")))
                .isInstanceOf(AccountLockedException.class).hasMessageContaining("24 hours");
        assertThat(security.getWrongPasswordCount()).isEqualTo(12);
    }

    @Test
    void login_failuresOlderThanADay_areForgotten() {
        ReflectionTestUtils.setField(security, "wrongPasswordCount", 4);
        ReflectionTestUtils.setField(security, "lastFailedAt", Instant.now().minus(Duration.ofDays(2)));

        assertThatThrownBy(() -> auth.login(new LoginRequest("asha@x.com", "wrong")))
                .isInstanceOf(AuthenticationFailedException.class);
        assertThat(security.getWrongPasswordCount()).isEqualTo(1);
        assertThat(security.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void login_permanentBlockSetBySupport_isStillHonoured() {
        ReflectionTestUtils.setField(security, "permanentBlock", true);

        assertThatThrownBy(() -> auth.login(new LoginRequest("asha@x.com", "Right#123")))
                .isInstanceOf(AccountLockedException.class).hasMessageContaining("contact support");
        verify(tokens, never()).issue(any());
    }

    @Test
    void blockDuration_doublesFromHalfAnHourUpToADay() {
        assertThat(LoginThrottle.blockDuration(5)).isEqualTo(Duration.ofMinutes(30));
        assertThat(LoginThrottle.blockDuration(6)).isEqualTo(Duration.ofHours(1));
        assertThat(LoginThrottle.blockDuration(9)).isEqualTo(Duration.ofHours(8));
        assertThat(LoginThrottle.blockDuration(11)).isEqualTo(Duration.ofHours(24));
        assertThat(LoginThrottle.blockDuration(1_000)).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void register_normalizesEmail_recordsWelcomeEvent_andSignsIn() {
        when(users.existsByEmail("new@x.com")).thenReturn(false);
        when(users.saveAndFlush(any())).thenAnswer(i -> {
            User u = i.getArgument(0);
            ReflectionTestUtils.setField(u, "id", 9L);
            return u;
        });

        auth.register(new RegisterRequest("  New Person ", " New@X.com", "", "Secret#123"));

        verify(users).saveAndFlush(argThat(u -> u.getEmail().equals("new@x.com") && u.getName().equals("New Person")
                && u.getRole() == Roles.ROLE_CUSTOMER && u.getPhone() == null
                && encoder.matches("Secret#123", u.getPassword())));
        verify(outbox).record(eq("User"), eq(9L), eq(OutboxEvents.USER_REGISTERED), any());
        verify(tokens).issue(any());
    }

    @Test
    void register_existingEmail_isAConflict() {
        when(users.existsByEmail("asha@x.com")).thenReturn(true);

        assertThatThrownBy(() -> auth.register(new RegisterRequest("Asha", "asha@x.com", null, "Secret#123")))
                .isInstanceOf(ConflictException.class);
        verifyNoInteractions(outbox, tokens);
    }

    @Test
    void changePassword_signsOutEverywhere() {
        auth.changePassword(7L, new ChangePasswordRequest("Right#123", "Newer#456"));

        assertThat(encoder.matches("Newer#456", user.getPassword())).isTrue();
        verify(tokens).revokeAll(7L);
    }

    @Test
    void changePassword_wrongCurrentPassword_isRejected() {
        assertThatThrownBy(() -> auth.changePassword(7L, new ChangePasswordRequest("wrong", "Newer#456")))
                .isInstanceOf(AuthenticationFailedException.class);
        verify(tokens, never()).revokeAll(any());
    }
}
