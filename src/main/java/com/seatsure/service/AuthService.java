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


@Slf4j
@Service
public class AuthService {

    static final String INVALID_CREDENTIALS = "Invalid email or password";

    private final UserRepository users;
    private final LoginSecurityRepository loginSecurity;
    private final LoginThrottle throttle;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    private final OutboxService outbox;
    /** Compared against when the email is unknown, so both cases take a BCrypt check (no timing oracle). */
    private final String dummyHash;

    public AuthService(UserRepository users, LoginSecurityRepository loginSecurity, LoginThrottle throttle,
                       PasswordEncoder passwordEncoder, TokenService tokens, OutboxService outbox) {
        this.users = users;
        this.loginSecurity = loginSecurity;
        this.throttle = throttle;
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
        user.changePhone(request.phone());
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
     *
     * <p>A blocked or deactivated account gets its own message, so these answers do reveal that an
     * email is registered; so does sign-up ("already exists"), which can't avoid it. Rate limits
     * make probing either one slow.
     */
    @Observed(name = "auth.login", contextualName = "login")
    @Transactional(noRollbackFor = {AuthenticationFailedException.class, AccountLockedException.class})
    public TokenResponse login(LoginRequest request) {
        User user = users.findByEmail(User.normalizeEmail(request.email())).orElse(null);
        if (user == null) {
            passwordEncoder.matches(request.password(), dummyHash);
            throw new AuthenticationFailedException(INVALID_CREDENTIALS);
        }
        LoginSecurity security = throttle.admit(user);
        if (!user.isActive()) {
            throw new AccountLockedException("This account is deactivated. Please contact support.");
        }
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throttle.failed(security); // throws if this attempt blocks the account
            throw new AuthenticationFailedException(INVALID_CREDENTIALS);
        }
        throttle.succeeded(security);
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
        user.changePassword(passwordEncoder.encode(request.newPassword()));
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
