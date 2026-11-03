package com.seatsure.service;

import com.seatsure.entity.LoginSecurity;
import com.seatsure.entity.User;
import com.seatsure.exception.AccountLockedException;
import com.seatsure.repository.LoginSecurityRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Brute-force protection for one account, used by {@link AuthService#login}.
 *
 * <p>Lockout never becomes permanent: anyone who knows an email address could otherwise lock its
 * owner out for good. Blocks escalate instead (30 min, 1 h, 2 h, ... up to a day), and failures
 * older than a day are forgotten. Per-IP rate limits slow guessing across accounts.
 *
 * <p>Must run inside the login transaction: the counters row is locked for the whole attempt, so
 * parallel wrong passwords are all counted instead of overwriting each other.
 */
@Slf4j
@Component
public class LoginThrottle {

    static final int BLOCK_THRESHOLD = 5;
    static final Duration FIRST_BLOCK = Duration.ofMinutes(30);
    static final Duration MAX_BLOCK = Duration.ofHours(24);
    static final Duration FAILURE_MEMORY = Duration.ofHours(24);
    private static final DateTimeFormatter UNTIL = DateTimeFormatter.ofPattern("d MMM, h:mm a z", Locale.ENGLISH);

    private final LoginSecurityRepository loginSecurity;
    private final Clock clock;
    private final ZoneId zone;

    public LoginThrottle(LoginSecurityRepository loginSecurity, Clock clock, ZoneId zone) {
        this.loginSecurity = loginSecurity;
        this.clock = clock;
        this.zone = zone;
    }

    /** Locks the account's counters and refuses the attempt while it is blocked. */
    public LoginSecurity admit(User user) {
        LoginSecurity security = loginSecurity.findByIdForUpdate(user.getId())
                .orElseGet(() -> loginSecurity.save(new LoginSecurity(user)));
        Instant now = clock.instant();
        if (security.isPermanentlyBlocked()) {
            throw new AccountLockedException("This account is blocked. Please contact support.");
        }
        if (security.isBlockedAt(now)) {
            throw new AccountLockedException("Too many failed attempts. Try again after "
                    + UNTIL.format(security.getBlockExpiryAt().atZone(zone)) + ".");
        }
        // Forget old failures before lifting an expired block: a block's own failures must still count,
        // so the next wrong password after it blocks for longer.
        security.forgetFailuresBefore(now.minus(FAILURE_MEMORY));
        security.liftExpiredBlock(now);
        return security;
    }

    /** A wrong password. Throws {@link AccountLockedException} if this one blocks the account. */
    public void failed(LoginSecurity security) {
        Instant now = clock.instant();
        int failures = security.recordFailure(now);
        if (failures >= BLOCK_THRESHOLD) {
            Duration block = blockDuration(failures);
            security.block(now, now.plus(block), failures + " wrong passwords");
            log.warn("Account {} blocked for {} after {} wrong passwords", security.getId(), block, failures);
            throw new AccountLockedException("Too many failed attempts. Try again in " + describe(block) + ".");
        }
    }

    public void succeeded(LoginSecurity security) {
        security.recordSuccess(clock.instant());
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
}
