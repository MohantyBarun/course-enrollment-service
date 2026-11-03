package com.seatsure.entity;

import com.seatsure.service.AccountStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Getter
@NoArgsConstructor
@Table(name = "login_security")
public class LoginSecurity {

    @Id
    private Long id;

    @Column(name = "wrong_password_count", nullable = false)
    private Integer wrongPasswordCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_status", nullable = false)
    private AccountStatus accountStatus = AccountStatus.ACTIVE;

    @Column(name = "permanent_block", nullable = false)
    private Boolean permanentBlock = false;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "last_failed_at")
    private Instant lastFailedAt;

    @Column(name = "blocked_at")
    private Instant blockedAt;

    @Column(name = "unblocked_at")
    private Instant unblockedAt;

    @Column(name = "block_expiry_at")
    private Instant blockExpiryAt;

    @Column(name = "block_reason")
    private String blockReason;

    @OneToOne
    @MapsId
    @JoinColumn(name = "id")
    private User user;

    public LoginSecurity(User user) {
        this.user = user;
    }

    /** Set only by support, never by failed logins. */
    public boolean isPermanentlyBlocked() {
        return Boolean.TRUE.equals(permanentBlock);
    }

    public boolean isBlockedAt(Instant now) {
        return accountStatus == AccountStatus.BLOCKED && now.isBefore(blockExpiryAt);
    }

    /** A block that has run out is lifted. Its failures still count, so the next one blocks for longer. */
    public void liftExpiredBlock(Instant now) {
        if (accountStatus == AccountStatus.BLOCKED && !now.isBefore(blockExpiryAt)) {
            accountStatus = AccountStatus.ACTIVE;
            unblockedAt = now;
        }
    }

    /** An old typo shouldn't count towards a block today. Doesn't apply while (or right after) blocked. */
    public void forgetFailuresBefore(Instant cutoff) {
        if (accountStatus != AccountStatus.BLOCKED && lastFailedAt != null && lastFailedAt.isBefore(cutoff)) {
            wrongPasswordCount = 0;
        }
    }

    /** @return the number of wrong passwords in a row, including this one */
    public int recordFailure(Instant now) {
        wrongPasswordCount++;
        lastFailedAt = now;
        return wrongPasswordCount;
    }

    public void block(Instant now, Instant until, String reason) {
        accountStatus = AccountStatus.BLOCKED;
        blockedAt = now;
        blockExpiryAt = until;
        blockReason = reason;
    }

    public void recordSuccess(Instant now) {
        wrongPasswordCount = 0;
        lastLoginAt = now;
    }
}
