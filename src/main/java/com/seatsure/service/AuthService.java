package com.seatsure.service;

import com.seatsure.dto.AuthDtos.ChangePasswordRequest;
import com.seatsure.dto.AuthDtos.LoginRequest;
import com.seatsure.dto.AuthDtos.RegisterRequest;
import com.seatsure.dto.AuthDtos.TokenResponse;
import com.seatsure.dto.AuthDtos.UserView;
import com.seatsure.entity.LoginSecurity;
import com.seatsure.entity.User;
import com.seatsure.exception.AccountLockedException;
import com.seatsure.exception.AuthenticationFailedException;
import com.seatsure.exception.BadRequestException;
import com.seatsure.exception.ConflictException;
import com.seatsure.exception.ResourceNotFoundException;
import com.seatsure.outbox.OutboxEvents;
import com.seatsure.outbox.OutboxService;
import com.seatsure.repository.LoginSecurityRepository;
import com.seatsure.repository.UserRepository;
import io.micrometer.observation.annotation.Observed;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
public class AuthService {

    static final int TEMP_BLOCK_THRESHOLD = 5;       // wrong passwords before a 30-minute block
    static final int PERMANENT_BLOCK_THRESHOLD = 10; // more than this => blocked until an admin steps in
    static final String INVALID_CREDENTIALS = "Invalid email or password";

    private final UserRepository users;
    private final LoginSecurityRepository loginSecurity;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    private final OutboxService outbox;
    /** Compared against when the email is unknown, so both cases take a BCrypt check (no timing oracle). */
    private final String dummyHash;

    public AuthService(UserRepository users, LoginSecurityRepository loginSecurity, PasswordEncoder passwordEncoder,
                       TokenService tokens, OutboxService outbox) {
        this.users = users;
        this.loginSecurity = loginSecurity;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.outbox = outbox;
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
    }

    @Transactional
    public TokenResponse register(RegisterRequest request) {
        String email = User.normalizeEmail(request.email());
        if (users.existsByEmail(email)) {
            throw new ConflictException("An account with this email already exists. Try logging in.");
        }
        User user = new User(request.name().trim(), email, passwordEncoder.encode(request.password()),
                Roles.ROLE_CUSTOMER);
        user.setPhone(request.phone() == null || request.phone().isBlank() ? null : request.phone().trim());
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Two sign-ups with the same email at once: the unique constraint decides
            throw new ConflictException("An account with this email already exists. Try logging in.");
        }
        loginSecurity.save(new LoginSecurity(user));
        outbox.record("User", user.getId(), OutboxEvents.USER_REGISTERED,
                new OutboxEvents.UserRegistered(user.getId(), user.getName(), user.getEmail()));
        log.info("Customer registered: {}", user.getId());
        return tokens.issue(user);
    }

    /**
     * noRollbackFor: a failed login records the failure (counters, block status) and then throws;
     * rolling that back would disable the brute-force protection.
     */
    @Observed(name = "auth.login", contextualName = "login")
    @Transactional(noRollbackFor = {AuthenticationFailedException.class, AccountLockedException.class})
    public TokenResponse login(LoginRequest request) {
        User user = users.findByEmail(User.normalizeEmail(request.email())).orElse(null);
        if (user == null) {
            passwordEncoder.matches(request.password(), dummyHash);
            throw new AuthenticationFailedException(INVALID_CREDENTIALS);
        }
        LoginSecurity security = loginSecurity.findById(user.getId())
                .orElseGet(() -> loginSecurity.save(new LoginSecurity(user)));
        LocalDateTime now = LocalDateTime.now();

        if (Boolean.TRUE.equals(security.getPermanentBlock())) {
            throw new AccountLockedException("This account is blocked. Please contact support.");
        }
        if (security.getAccountStatus() == AccountStatus.BLOCKED) {
            if (now.isBefore(security.getBlockExpiryAt())) {
                throw new AccountLockedException("Too many failed attempts. Try again after "
                        + security.getBlockExpiryAt().withNano(0).toLocalTime() + ".");
            }
            // The block has expired. The failure count is kept, so repeated guessing still reaches the permanent block.
            security.setAccountStatus(AccountStatus.ACTIVE);
            security.setUnblockedAt(now);
        }
        if (!user.isActive()) {
            throw new AccountLockedException("This account is deactivated. Please contact support.");
        }

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            int failures = security.getWrongPasswordCount() + 1;
            security.setWrongPasswordCount(failures);
            security.setLastFailedAt(now);
            if (failures > PERMANENT_BLOCK_THRESHOLD) {
                security.setPermanentBlock(true);
                security.setAccountStatus(AccountStatus.PERMANENTLY_BLOCKED);
                security.setBlockedAt(now);
                security.setBlockReason("More than " + PERMANENT_BLOCK_THRESHOLD + " wrong passwords");
                log.warn("Account {} permanently blocked", user.getId());
                throw new AccountLockedException("Too many failed attempts. This account is now blocked.");
            }
            if (failures >= TEMP_BLOCK_THRESHOLD) {
                security.setAccountStatus(AccountStatus.BLOCKED);
                security.setBlockedAt(now);
                security.setBlockExpiryAt(now.plusMinutes(30));
                security.setBlockReason(failures + " wrong passwords");
                log.warn("Account {} blocked for 30 minutes", user.getId());
                throw new AccountLockedException("Too many failed attempts. Try again in 30 minutes.");
            }
            throw new AuthenticationFailedException(INVALID_CREDENTIALS);
        }

        security.setWrongPasswordCount(0);
        security.setLastLoginAt(now);
        return tokens.issue(user);
    }

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("Account not found"));
        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            throw new AuthenticationFailedException("Current password is incorrect");
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPassword())) {
            throw new BadRequestException("The new password must be different from the current one");
        }
        user.setPassword(passwordEncoder.encode(request.newPassword()));
        // Sign out every other session; the caller's access token stays valid until it expires (15 min)
        tokens.revokeAll(userId);
        log.info("Password changed for user {}", userId);
    }

    @Transactional(readOnly = true)
    public UserView me(Long userId) {
        return users.findById(userId).map(UserView::of)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
    }
}
