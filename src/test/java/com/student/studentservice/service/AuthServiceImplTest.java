package com.student.studentservice.service;

import com.student.studentservice.dto.*;
import com.student.studentservice.entity.LoginSecurity;
import com.student.studentservice.entity.RefreshToken;
import com.student.studentservice.entity.User;
import com.student.studentservice.exception.AccountLockedException;
import com.student.studentservice.exception.AuthenticationFailedException;
import com.student.studentservice.exception.BadRequestException;
import com.student.studentservice.exception.ResourceNotFoundException;
import com.student.studentservice.repository.LoginSecurityRepository;
import com.student.studentservice.repository.RefreshTokenRepository;
import com.student.studentservice.repository.UserRepository;
import com.student.studentservice.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    private static final String EMAIL = "student@college.com";
    private static final String PASSWORD = "Correct@123";
    private static final String TEMP_PASSWORD = "Temp#1234";

    @Mock private UserRepository userRepository;
    @Mock private LoginSecurityRepository loginSecurityRepository;
    @Mock private JwtUtil jwtUtil;
    @Mock private RefreshTokenRepository refreshTokenRepository;

    // Real encoder (low cost factor for speed) so password checks behave exactly as in production
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

    private AuthServiceImpl authService;
    private User user;
    private LoginSecurity loginSecurity;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(userRepository, loginSecurityRepository, passwordEncoder,
                jwtUtil, refreshTokenRepository);

        user = new User();
        user.setId(1L);
        user.setEmail(EMAIL);
        user.setRole(Roles.ROLE_STUDENT);
        user.setIsActive(true);
        user.setIsFirstTimeLogin(false);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setTempPasswordAttemptsCount(0);

        loginSecurity = new LoginSecurity();
        loginSecurity.setId(1L);
        loginSecurity.setUser(user);
        loginSecurity.setAccountStatus(AccountStatus.ACTIVE);
        loginSecurity.setWrongPasswordCount(0);
        loginSecurity.setPermanentBlock(false);
    }

    private void givenUserExists() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(loginSecurityRepository.findByUserId(1L)).thenReturn(Optional.of(loginSecurity));
    }

    private void givenFirstTimeUser() {
        user.setIsFirstTimeLogin(true);
        user.setPassword(null);
        user.setTempPassword(passwordEncoder.encode(TEMP_PASSWORD));
        user.setTempPasswordExpiry(LocalDateTime.now().plusHours(48));
    }

    private static LoginRequestDTO login(String password) {
        LoginRequestDTO dto = new LoginRequestDTO();
        dto.setEmail(EMAIL);
        dto.setPassword(password);
        return dto;
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("userLogin")
    class UserLogin {

        @Test
        void unknownEmail_throwsNotFound() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.userLogin(login(PASSWORD)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void correctPassword_returnsTokensAndResetsFailureCount() {
            givenUserExists();
            loginSecurity.setWrongPasswordCount(3);
            when(jwtUtil.generateAccessToken(EMAIL, Roles.ROLE_STUDENT, false)).thenReturn("access");
            when(jwtUtil.generateRefreshToken(EMAIL)).thenReturn("refresh");

            LoginResponseDTO response = authService.userLogin(login(PASSWORD));

            assertThat(response.getAccessToken()).isEqualTo("access");
            assertThat(response.getRefreshToken()).isEqualTo("refresh");
            assertThat(response.getTokenType()).isEqualTo("Bearer");
            assertThat(response.getIsFirstTimeLogin()).isFalse();
            assertThat(loginSecurity.getWrongPasswordCount()).isZero();
            assertThat(loginSecurity.getLastLoginAt()).isNotNull();

            ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
            verify(refreshTokenRepository).save(saved.capture());
            assertThat(saved.getValue().getToken()).isEqualTo("refresh");
            assertThat(saved.getValue().getUser()).isSameAs(user);
            assertThat(saved.getValue().getIsRevoked()).isFalse();
            assertThat(saved.getValue().getExpiryDate()).isAfter(LocalDateTime.now().plusDays(6));
        }

        @Test
        void missingLoginSecurityRecord_throwsNotFound() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
            when(loginSecurityRepository.findByUserId(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.userLogin(login(PASSWORD)))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Login security record not found");
            verifyNoInteractions(jwtUtil);
        }

        @Test
        void permanentlyBlocked_throwsLockedEvenWithCorrectPassword() {
            givenUserExists();
            loginSecurity.setPermanentBlock(true);

            assertThatThrownBy(() -> authService.userLogin(login(PASSWORD)))
                    .isInstanceOf(AccountLockedException.class)
                    .hasMessageContaining("permanently blocked");
            verifyNoInteractions(jwtUtil);
        }

        @Test
        void temporarilyBlocked_beforeExpiry_throwsLocked() {
            givenUserExists();
            loginSecurity.setAccountStatus(AccountStatus.BLOCKED);
            loginSecurity.setBlockExpiryAt(LocalDateTime.now().plusMinutes(10));

            assertThatThrownBy(() -> authService.userLogin(login(PASSWORD)))
                    .isInstanceOf(AccountLockedException.class)
                    .hasMessageContaining("temporarily blocked");
        }

        @Test
        void temporarilyBlocked_afterExpiry_autoUnblocksAndLogsIn() {
            givenUserExists();
            loginSecurity.setAccountStatus(AccountStatus.BLOCKED);
            loginSecurity.setWrongPasswordCount(5);
            loginSecurity.setBlockExpiryAt(LocalDateTime.now().minusMinutes(1));

            authService.userLogin(login(PASSWORD));

            assertThat(loginSecurity.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(loginSecurity.getUnblockedAt()).isNotNull();
            assertThat(loginSecurity.getWrongPasswordCount()).isZero();
        }

        @Test
        @DisplayName("regression: auto-unblock keeps the failure count so the permanent block is reachable")
        void autoUnblock_thenWrongPassword_keepsCountAndReblocks() {
            givenUserExists();
            loginSecurity.setAccountStatus(AccountStatus.BLOCKED);
            loginSecurity.setWrongPasswordCount(5);
            loginSecurity.setBlockExpiryAt(LocalDateTime.now().minusMinutes(1));

            assertThatThrownBy(() -> authService.userLogin(login("Wrong@123")))
                    .isInstanceOf(AccountLockedException.class)
                    .hasMessageContaining("temporarily blocked");
            assertThat(loginSecurity.getWrongPasswordCount()).isEqualTo(6);
            assertThat(loginSecurity.getAccountStatus()).isEqualTo(AccountStatus.BLOCKED);
        }

        @Test
        void deactivatedAccount_throwsLocked() {
            givenUserExists();
            user.setIsActive(false);

            assertThatThrownBy(() -> authService.userLogin(login(PASSWORD)))
                    .isInstanceOf(AccountLockedException.class)
                    .hasMessageContaining("deactivated");
        }

        @Test
        void wrongPassword_incrementsCountAndReportsRemainingAttempts() {
            givenUserExists();

            assertThatThrownBy(() -> authService.userLogin(login("Wrong@123")))
                    .isInstanceOf(AuthenticationFailedException.class)
                    .hasMessageContaining("Attempts remaining before temporary block: 4");
            assertThat(loginSecurity.getWrongPasswordCount()).isEqualTo(1);
            assertThat(loginSecurity.getLastFailedAt()).isNotNull();
            verify(loginSecurityRepository).save(loginSecurity);
        }

        @Test
        void fifthWrongPassword_blocksTemporarilyFor30Minutes() {
            givenUserExists();
            loginSecurity.setWrongPasswordCount(4);

            assertThatThrownBy(() -> authService.userLogin(login("Wrong@123")))
                    .isInstanceOf(AccountLockedException.class)
                    .hasMessageContaining("temporarily blocked");
            assertThat(loginSecurity.getAccountStatus()).isEqualTo(AccountStatus.BLOCKED);
            assertThat(loginSecurity.getBlockExpiryAt())
                    .isBetween(LocalDateTime.now().plusMinutes(29), LocalDateTime.now().plusMinutes(31));
            assertThat(loginSecurity.getPermanentBlock()).isFalse();
            verify(loginSecurityRepository).save(loginSecurity);
        }

        @Test
        void eleventhWrongPassword_blocksPermanently() {
            givenUserExists();
            loginSecurity.setWrongPasswordCount(10);

            assertThatThrownBy(() -> authService.userLogin(login("Wrong@123")))
                    .isInstanceOf(AccountLockedException.class)
                    .hasMessageContaining("permanently blocked");
            assertThat(loginSecurity.getPermanentBlock()).isTrue();
            assertThat(loginSecurity.getAccountStatus()).isEqualTo(AccountStatus.PERMANENTLY_BLOCKED);
            assertThat(loginSecurity.getBlockReason()).contains("10");
            verify(loginSecurityRepository).save(loginSecurity);
        }

        @Test
        void firstTimeLogin_withTempPassword_returnsRestrictedToken() {
            givenUserExists();
            givenFirstTimeUser();
            when(jwtUtil.generateAccessToken(EMAIL, Roles.ROLE_STUDENT, true)).thenReturn("restricted");
            when(jwtUtil.generateRefreshToken(EMAIL)).thenReturn("refresh");

            LoginResponseDTO response = authService.userLogin(login(TEMP_PASSWORD));

            assertThat(response.getAccessToken()).isEqualTo("restricted");
            assertThat(response.getIsFirstTimeLogin()).isTrue();
            verify(jwtUtil).generateAccessToken(EMAIL, Roles.ROLE_STUDENT, true);
        }

        @Test
        void firstTimeLogin_wrongTempPassword_incrementsAttempts() {
            givenUserExists();
            givenFirstTimeUser();

            assertThatThrownBy(() -> authService.userLogin(login("bad")))
                    .isInstanceOf(AuthenticationFailedException.class)
                    .hasMessageContaining("Attempts remaining: 2");
            assertThat(user.getTempPasswordAttemptsCount()).isEqualTo(1);
            verify(userRepository).save(user);
        }

        @Test
        void firstTimeLogin_afterThreeFailedAttempts_throwsLockedEvenWithCorrectPassword() {
            givenUserExists();
            givenFirstTimeUser();
            user.setTempPasswordAttemptsCount(3);

            assertThatThrownBy(() -> authService.userLogin(login(TEMP_PASSWORD)))
                    .isInstanceOf(AccountLockedException.class)
                    .hasMessageContaining("Max temp password attempts");
        }

        @Test
        void firstTimeLogin_expiredTempPassword_throwsLocked() {
            givenUserExists();
            givenFirstTimeUser();
            user.setTempPasswordExpiry(LocalDateTime.now().minusMinutes(1));

            assertThatThrownBy(() -> authService.userLogin(login(TEMP_PASSWORD)))
                    .isInstanceOf(AccountLockedException.class)
                    .hasMessageContaining("expired");
        }

        @Test
        @DisplayName("regression: failed-login writes must not be rolled back by the exception that follows them")
        void userLogin_doesNotRollBackOnAuthFailures() throws NoSuchMethodException {
            Transactional tx = AuthServiceImpl.class
                    .getMethod("userLogin", LoginRequestDTO.class)
                    .getAnnotation(Transactional.class);

            assertThat(tx).as("userLogin must be @Transactional (Spring)").isNotNull();
            assertThat(tx.noRollbackFor())
                    .contains(AuthenticationFailedException.class, AccountLockedException.class);
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("changePassword")
    class ChangePassword {

        private ChangePasswordRequestDTO request(String oldPw, String newPw, String confirm) {
            ChangePasswordRequestDTO dto = new ChangePasswordRequestDTO();
            dto.setOldPassword(oldPw);
            dto.setNewPassword(newPw);
            dto.setConfirmNewPassword(confirm);
            return dto;
        }

        @Test
        void firstTimeUser_verifiesTempPasswordAndClearsIt() {
            givenUserExists();
            givenFirstTimeUser();
            user.setTempPasswordAttemptsCount(2);

            String result = authService.changePassword(EMAIL, request(TEMP_PASSWORD, "NewPass@123", "NewPass@123"));

            assertThat(result).contains("Password changed successfully");
            assertThat(passwordEncoder.matches("NewPass@123", user.getPassword())).isTrue();
            assertThat(user.getTempPassword()).isNull();
            assertThat(user.getTempPasswordExpiry()).isNull();
            assertThat(user.getTempPasswordAttemptsCount()).isZero();
            assertThat(user.getIsFirstTimeLogin()).isFalse();
            verify(userRepository).save(user);
        }

        @Test
        @DisplayName("regression: after the first change, the old password is checked against the real password")
        void returningUser_verifiesRealPassword() {
            givenUserExists();

            authService.changePassword(EMAIL, request(PASSWORD, "NewPass@123", "NewPass@123"));

            assertThat(passwordEncoder.matches("NewPass@123", user.getPassword())).isTrue();
        }

        @Test
        void wrongOldPassword_throwsAuthFailed() {
            givenUserExists();

            assertThatThrownBy(() -> authService.changePassword(EMAIL, request("nope", "NewPass@123", "NewPass@123")))
                    .isInstanceOf(AuthenticationFailedException.class);
            verify(userRepository, never()).save(any());
        }

        @Test
        void confirmationMismatch_throwsBadRequest() {
            givenUserExists();

            assertThatThrownBy(() -> authService.changePassword(EMAIL, request(PASSWORD, "NewPass@123", "Other@123")))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("do not match");
        }

        @Test
        void newPasswordSameAsOld_throwsBadRequest() {
            givenUserExists();

            assertThatThrownBy(() -> authService.changePassword(EMAIL, request(PASSWORD, PASSWORD, PASSWORD)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("same as old");
        }

        @Test
        void permanentlyBlockedUser_throwsLocked() {
            givenUserExists();
            loginSecurity.setPermanentBlock(true);

            assertThatThrownBy(() -> authService.changePassword(EMAIL, request(PASSWORD, "NewPass@123", "NewPass@123")))
                    .isInstanceOf(AccountLockedException.class);
        }

        @Test
        void accountWithPermanentlyBlockedStatus_throwsLocked() {
            // status set (e.g. by an admin) without the permanentBlock flag
            givenUserExists();
            loginSecurity.setAccountStatus(AccountStatus.PERMANENTLY_BLOCKED);

            assertThatThrownBy(() -> authService.changePassword(EMAIL, request(PASSWORD, "NewPass@123", "NewPass@123")))
                    .isInstanceOf(AccountLockedException.class);
        }

        @Test
        void noStoredPassword_throwsAuthFailedInsteadOfCrashing() {
            givenUserExists();
            user.setPassword(null);

            assertThatThrownBy(() -> authService.changePassword(EMAIL, request(PASSWORD, "NewPass@123", "NewPass@123")))
                    .isInstanceOf(AuthenticationFailedException.class);
        }

        @Test
        void unknownUser_throwsNotFound() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.changePassword(EMAIL, request(PASSWORD, "NewPass@123", "NewPass@123")))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void missingLoginSecurityRecord_throwsNotFound() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
            when(loginSecurityRepository.findByUserId(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.changePassword(EMAIL, request(PASSWORD, "NewPass@123", "NewPass@123")))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("refreshToken and logout")
    class RefreshAndLogout {

        private RefreshToken storedToken;

        @BeforeEach
        void setUpToken() {
            storedToken = new RefreshToken();
            storedToken.setToken("refresh");
            storedToken.setUser(user);
            storedToken.setIsRevoked(false);
            storedToken.setExpiryDate(LocalDateTime.now().plusDays(1));
        }

        private RefreshTokenRequestDTO request() {
            RefreshTokenRequestDTO dto = new RefreshTokenRequestDTO();
            dto.setRefreshToken("refresh");
            return dto;
        }

        @Test
        void validToken_issuesNewAccessToken() {
            when(refreshTokenRepository.findByToken("refresh")).thenReturn(Optional.of(storedToken));
            when(jwtUtil.generateAccessToken(EMAIL, Roles.ROLE_STUDENT, false)).thenReturn("new-access");

            assertThat(authService.refreshToken(request()).getAccessToken()).isEqualTo("new-access");
        }

        @Test
        void firstTimeUser_refreshStillIssuesRestrictedToken() {
            user.setIsFirstTimeLogin(true);
            when(refreshTokenRepository.findByToken("refresh")).thenReturn(Optional.of(storedToken));

            authService.refreshToken(request());

            verify(jwtUtil).generateAccessToken(EMAIL, Roles.ROLE_STUDENT, true);
        }

        @Test
        void unknownToken_throwsAuthFailed() {
            when(refreshTokenRepository.findByToken("refresh")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.refreshToken(request()))
                    .isInstanceOf(AuthenticationFailedException.class);
        }

        @Test
        void revokedToken_throwsAuthFailed() {
            storedToken.setIsRevoked(true);
            when(refreshTokenRepository.findByToken("refresh")).thenReturn(Optional.of(storedToken));

            assertThatThrownBy(() -> authService.refreshToken(request()))
                    .isInstanceOf(AuthenticationFailedException.class)
                    .hasMessageContaining("revoked");
        }

        @Test
        void expiredToken_throwsAuthFailed() {
            storedToken.setExpiryDate(LocalDateTime.now().minusSeconds(1));
            when(refreshTokenRepository.findByToken("refresh")).thenReturn(Optional.of(storedToken));

            assertThatThrownBy(() -> authService.refreshToken(request()))
                    .isInstanceOf(AuthenticationFailedException.class)
                    .hasMessageContaining("expired");
        }

        @Test
        void logout_revokesAllTokensOfTheUser() {
            when(refreshTokenRepository.findByToken("refresh")).thenReturn(Optional.of(storedToken));

            assertThat(authService.logout(request())).contains("Logged out");
            verify(refreshTokenRepository).revokeAllTokensByUser(user);
        }

        @Test
        void logout_unknownToken_throwsAuthFailed() {
            when(refreshTokenRepository.findByToken("refresh")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.logout(request()))
                    .isInstanceOf(AuthenticationFailedException.class);
            verify(refreshTokenRepository, never()).revokeAllTokensByUser(any());
        }

        @Test
        @DisplayName("regression: refresh/logout run in a transaction so the lazy token->user link can load")
        void refreshAndLogout_areTransactional() throws NoSuchMethodException {
            assertThat(AuthServiceImpl.class.getMethod("refreshToken", RefreshTokenRequestDTO.class)
                    .getAnnotation(Transactional.class)).isNotNull();
            assertThat(AuthServiceImpl.class.getMethod("logout", RefreshTokenRequestDTO.class)
                    .getAnnotation(Transactional.class)).isNotNull();
        }
    }
}
