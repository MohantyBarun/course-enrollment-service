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

import java.time.Duration;
import java.time.LocalDateTime;

@Slf4j
@Service
public class AuthService {

    /**
     * Lockout never becomes permanent: anyone who knows an email address could otherwise lock its
     * owner out for good. Blocks escalate instead (30 min, 1 h, 2 h, ... up to a day), and failures
     * older than a day are forgotten. Per-IP rate limits slow guessing further.
     */
    static final int BLOCK_THRESHOLD = 5;
    static final Duration FIRST_BLOCK = Duration.ofMinutes(30);
    static final Duration MAX_BLOCK = Duration.ofHours(24);
    static final Duration FAILURE_MEMORY = Duration.ofHours(24);
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
     * rolling that back would disable the brute-force protection. The counter row is locked, so
     * parallel guesses are counted one after another instead of overwriting each other.
     */
    @Observed(name = "auth.login", contextualName = "login")
    @Transactional(noRollbackFor = {AuthenticationFailedException.class, AccountLockedException.class})
    public TokenResponse login(LoginRequest request) {
        User user = users.findByEmail(User.normalizeEmail(request.email())).orElse(null);
        if (user == null) {
            passwordEncoder.matches(request.password(), dummyHash);
            throw new AuthenticationFailedException(INVALID_CREDENTIALS);
        }
        LoginSecurity security = loginSecurity.findByIdForUpdate(user.getId())
                .orElseGet(() -> loginSecurity.save(new LoginSecurity(user)));
        LocalDateTime now = LocalDateTime.now();

        // Set only by hand (support), never by failed logins
        if (Boolean.TRUE.equals(security.getPermanentBlock())) {
            throw new AccountLockedException("This account is blocked. Please contact support.");
        }
        if (security.getAccountStatus() == AccountStatus.BLOCKED) {
            if (now.isBefore(security.getBlockExpiryAt())) {
                throw new AccountLockedException("Too many failed attempts. Try again after "
                        + security.getBlockExpiryAt().withNano(0).toLocalTime() + ".");
            }
            // The block has expired. The count is kept, so the next wrong password blocks for longer.
            security.setAccountStatus(AccountStatus.ACTIVE);
            security.setUnblockedAt(now);
        } else if (security.getLastFailedAt() != null && security.getLastFailedAt().isBefore(now.minus(FAILURE_MEMORY))) {
            security.setWrongPasswordCount(0); // an old typo shouldn't count towards a block today
        }
        if (!user.isActive()) {
            throw new AccountLockedException("This account is deactivated. Please contact support.");
        }

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            int failures = security.getWrongPasswordCount() + 1;
            security.setWrongPasswordCount(failures);
            security.setLastFailedAt(now);
            if (failures >= BLOCK_THRESHOLD) {
                Duration block = blockDuration(failures);
                security.setAccountStatus(AccountStatus.BLOCKED);
                security.setBlockedAt(now);
                security.setBlockExpiryAt(now.plus(block));
                security.setBlockReason(failures + " wrong passwords");
                log.warn("Account {} blocked for {} after {} wrong passwords", user.getId(), block, failures);
                throw new AccountLockedException("Too many failed attempts. Try again in " + describe(block) + ".");
            }
            throw new AuthenticationFailedException(INVALID_CREDENTIALS);
        }

        security.setWrongPasswordCount(0);
        security.setLastLoginAt(now);
        return tokens.issue(user);
    }

    /** 30 min at the 5th wrong password, doubling with each one after, capped at a day. */
    static Duration blockDuration(int failures) {
        int doublings = Math.min(failures - BLOCK_THRESHOLD, 10); // 2^10 x 30 min is already past the cap
        Duration block = FIRST_BLOCK.multipliedBy(1L << doublings);
        return block.compareTo(MAX_BLOCK) > 0 ? MAX_BLOCK : block;
    }

    private static String describe(Duration block) {
        long hours = block.toHours();
        return hours >= 1 ? hours + (hours == 1 ? " hour" : " hours") : block.toMinutes() + " minutes";
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
