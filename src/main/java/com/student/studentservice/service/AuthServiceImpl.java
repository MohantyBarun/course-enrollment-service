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
import io.micrometer.observation.annotation.Observed;
import org.springframework.transaction.annotation.Transactional;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;

@Service
@Slf4j
@AllArgsConstructor
public class AuthServiceImpl implements AuthService{
    private static final int TEMP_BLOCK_THRESHOLD = 5;       // wrong attempts before a 30-min block
    private static final int PERMANENT_BLOCK_THRESHOLD = 10; // wrong attempts above this => permanent block
    private static final int MAX_TEMP_PASSWORD_ATTEMPTS = 3;

    private final UserRepository userRepository;
    private final LoginSecurityRepository loginSecurityRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final RefreshTokenRepository refreshTokenRepository;

    // A failed login records the failure (attempt counters, block status) and THEN throws.
    // Without noRollbackFor, the exception would roll back those writes and the
    // brute-force protection would never trigger.
    @Observed(name = "auth.login", contextualName = "login") // timer tagged with the outcome (error=none/...)
    @Transactional(noRollbackFor = {AuthenticationFailedException.class, AccountLockedException.class})
    @Override
    public LoginResponseDTO userLogin(LoginRequestDTO loginRequestDTO){
        log.info("Login attempted for "+ loginRequestDTO.getEmail());
        // First we have to check if user exists or not
        User user= userRepository.findByEmail(loginRequestDTO.getEmail()).orElseThrow(()-> new ResourceNotFoundException("No account found with email: "+ loginRequestDTO.getEmail()));

        // Step 2 - Get login security record
        LoginSecurity loginSecurity = loginSecurityRepository.findByUserId(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Login security record not found for user: " +loginRequestDTO.getEmail()));

        // Step 3 - Check if account is permanently blocked
        if (loginSecurity.getPermanentBlock()) {
            log.warn("Permanently blocked account login attempt: {}", loginRequestDTO.getEmail());
            throw new AccountLockedException("Account permanently blocked. Please contact admin.");
        }

        // Step 4 - Check if account is temporarily blocked
        if (loginSecurity.getAccountStatus() == AccountStatus.BLOCKED) {
            if (LocalDateTime.now().isBefore(loginSecurity.getBlockExpiryAt())) {
                log.warn("Temporarily blocked account login attempt: {}", loginRequestDTO.getEmail());
                throw new AccountLockedException("Account temporarily blocked. Try again after: "
                        + loginSecurity.getBlockExpiryAt());
            } else {
                // auto unblock — block duration has passed. The wrong-password count is
                // deliberately NOT reset, otherwise the permanent block could never be reached.
                log.info("Auto unblocking account: {}", loginRequestDTO.getEmail());
                loginSecurity.setAccountStatus(AccountStatus.ACTIVE);
                loginSecurity.setUnblockedAt(LocalDateTime.now());
                loginSecurityRepository.save(loginSecurity);
            }
        }

        // Step 5 - Check if account is active
        if (!user.getIsActive()) {
            throw new AccountLockedException("Account is deactivated. Please contact admin.");
        }

        // Step 6 - Verify password
        boolean isPasswordValid = false;

        // Step 6a - Check if first time login (temp password)
        if (user.getIsFirstTimeLogin()) {
            log.info("First time login attempt for: {}", loginRequestDTO.getEmail());

            // check temp password expiry
            if (LocalDateTime.now().isAfter(user.getTempPasswordExpiry())) {
                throw new AccountLockedException("Temp password expired. Please contact admin.");
            }

            // check temp password attempts
            if (user.getTempPasswordAttemptsCount() >= MAX_TEMP_PASSWORD_ATTEMPTS) {
                throw new AccountLockedException("Max temp password attempts exceeded. Contact admin.");
            }

            // verify temp password
            isPasswordValid = passwordEncoder.matches(
                    loginRequestDTO.getPassword(), user.getTempPassword());

            if (!isPasswordValid) {
                // increment temp password attempts
                user.setTempPasswordAttemptsCount(user.getTempPasswordAttemptsCount() + 1);
                userRepository.save(user);
                log.warn("Wrong temp password for: {} attempt: {}",
                        loginRequestDTO.getEmail(), user.getTempPasswordAttemptsCount());
                throw new AuthenticationFailedException("Invalid temp password. Attempts remaining: "
                        + (MAX_TEMP_PASSWORD_ATTEMPTS - user.getTempPasswordAttemptsCount()));
            }

        } else {
            // Step 6b - Regular password check
            isPasswordValid = passwordEncoder.matches(
                    loginRequestDTO.getPassword(), user.getPassword());

            if (!isPasswordValid) {
                // increment wrong password count
                int wrongCount = loginSecurity.getWrongPasswordCount() + 1;
                loginSecurity.setWrongPasswordCount(wrongCount);
                loginSecurity.setLastFailedAt(LocalDateTime.now());
                log.warn("Wrong password for: {} count: {}", loginRequestDTO.getEmail(), wrongCount);

                // check if should block
                if (wrongCount > PERMANENT_BLOCK_THRESHOLD) {
                    // permanently block
                    loginSecurity.setPermanentBlock(true);
                    loginSecurity.setAccountStatus(AccountStatus.PERMANENTLY_BLOCKED);
                    loginSecurity.setBlockedAt(LocalDateTime.now());
                    loginSecurity.setBlockReason("Exceeded max wrong password attempts ("
                            + PERMANENT_BLOCK_THRESHOLD + ")");
                    loginSecurityRepository.save(loginSecurity);
                    log.warn("Account permanently blocked: {}", loginRequestDTO.getEmail());
                    throw new AccountLockedException(
                            "Too many failed attempts. Account permanently blocked. Please contact admin.");
                }
                if (wrongCount >= TEMP_BLOCK_THRESHOLD) {
                    // temporarily block for 30 mins
                    loginSecurity.setAccountStatus(AccountStatus.BLOCKED);
                    loginSecurity.setBlockedAt(LocalDateTime.now());
                    loginSecurity.setBlockExpiryAt(LocalDateTime.now().plusMinutes(30));
                    loginSecurity.setBlockReason(wrongCount + " wrong password attempts");
                    loginSecurityRepository.save(loginSecurity);
                    log.warn("Account temporarily blocked: {}", loginRequestDTO.getEmail());
                    throw new AccountLockedException("Too many failed attempts. Account temporarily blocked until: "
                            + loginSecurity.getBlockExpiryAt());
                }

                loginSecurityRepository.save(loginSecurity);
                throw new AuthenticationFailedException("Invalid password. Attempts remaining before temporary block: "
                        + (TEMP_BLOCK_THRESHOLD - wrongCount));
            }
        }

        // Step 7 - Password correct — reset wrong count
        loginSecurity.setWrongPasswordCount(0);
        loginSecurity.setLastLoginAt(LocalDateTime.now());
        loginSecurityRepository.save(loginSecurity);

        // Step 8 - Generate tokens (first-time users get a token restricted to changing their password)
        String accessToken = jwtUtil.generateAccessToken(
                user.getEmail(), user.getRole(), user.getIsFirstTimeLogin());
        String refreshTokenString = jwtUtil.generateRefreshToken(user.getEmail());

        // Step 8a - Save refresh token to DB
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setToken(refreshTokenString);
        refreshToken.setUser(user);
        refreshToken.setExpiryDate(LocalDateTime.now().plusDays(7));
        refreshToken.setIsRevoked(false);
        refreshTokenRepository.save(refreshToken);

        log.info("Login successful for: {}", loginRequestDTO.getEmail());

        // Step 9 - Build and return response
        LoginResponseDTO response = new LoginResponseDTO();
        response.setAccessToken(accessToken);
        response.setRefreshToken(refreshTokenString);
        response.setTokenType("Bearer");
        response.setRole(user.getRole());
        response.setEmail(user.getEmail());
        response.setIsFirstTimeLogin(user.getIsFirstTimeLogin());

        return response;
    }

    @Transactional
    @Override
    public String changePassword(String email, ChangePasswordRequestDTO changePasswordRequestDTO) {
        // email comes from the authenticated JWT, never from the request body
        log.info("Change password attempted for {}", email);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + email));

        LoginSecurity loginSecurity = loginSecurityRepository.findByUserId(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Login security record not found"));

        if (loginSecurity.getPermanentBlock()
                || loginSecurity.getAccountStatus() == AccountStatus.PERMANENTLY_BLOCKED) {
            throw new AccountLockedException("Account is permanently blocked");
        }

        // First-time users prove themselves with the temp password, everyone else with their real one
        String currentPasswordHash = user.getIsFirstTimeLogin() ? user.getTempPassword() : user.getPassword();

        if (currentPasswordHash == null
                || !passwordEncoder.matches(changePasswordRequestDTO.getOldPassword(), currentPasswordHash)) {
            throw new AuthenticationFailedException("Old password is incorrect");
        }

        if (!changePasswordRequestDTO.getNewPassword().equals(changePasswordRequestDTO.getConfirmNewPassword())) {
            throw new BadRequestException("New password and confirm password do not match");
        }

        if (passwordEncoder.matches(changePasswordRequestDTO.getNewPassword(), currentPasswordHash)) {
            throw new BadRequestException("New password cannot be same as old password");
        }

        user.setPassword(passwordEncoder.encode(changePasswordRequestDTO.getNewPassword()));
        user.setTempPassword(null);
        user.setTempPasswordExpiry(null);
        user.setTempPasswordAttemptsCount(0);
        user.setIsFirstTimeLogin(false);
        user.setUpdatedAt(LocalDateTime.now());

        userRepository.save(user);
        log.info("Password changed successfully for {}", email);
        // The caller's current access token still carries pwdChangeRequired=true; a refresh issues a clean one.
        return "Password changed successfully. Please refresh your access token or log in again.";
    }

    // Transactional so the lazily-loaded RefreshToken.user can be read (open-in-view is disabled)
    @Transactional
    @Override
    public RefreshTokenResponseDTO refreshToken(RefreshTokenRequestDTO request) {
        String token = request.getRefreshToken();
        RefreshToken refreshToken = refreshTokenRepository.findByToken(token)
                .orElseThrow(() -> new AuthenticationFailedException("Refresh token not found"));

        if (refreshToken.getIsRevoked()) {
            throw new AuthenticationFailedException("Refresh token has been revoked");
        }
        if (refreshToken.getExpiryDate().isBefore(LocalDateTime.now())) {
            throw new AuthenticationFailedException("Refresh token has expired. Please login again");
        }

        User user = refreshToken.getUser();
        String newAccessToken = jwtUtil.generateAccessToken(
                user.getEmail(),
                user.getRole(),
                user.getIsFirstTimeLogin()
        );

        return new RefreshTokenResponseDTO(newAccessToken);
    }

    @Transactional
    @Override
    public String logout(RefreshTokenRequestDTO request) {
        String token = request.getRefreshToken();
        RefreshToken refreshToken = refreshTokenRepository.findByToken(token)
                .orElseThrow(() -> new AuthenticationFailedException("Refresh token not found"));

        refreshTokenRepository.revokeAllTokensByUser(refreshToken.getUser());

        return "Logged out successfully";
    }

}
