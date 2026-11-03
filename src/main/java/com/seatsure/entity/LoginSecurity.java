package com.seatsure.entity;

import com.seatsure.service.AccountStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
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
    private LocalDateTime lastLoginAt;

    @Column(name = "last_failed_at")
    private LocalDateTime lastFailedAt;

    @Column(name = "blocked_at")
    private LocalDateTime blockedAt;

    @Column(name = "unblocked_at")
    private LocalDateTime unblockedAt;

    @Column(name = "block_expiry_at")
    private LocalDateTime blockExpiryAt;

    @Column(name = "block_reason")
    private String blockReason;

    @OneToOne
    @MapsId
    @JoinColumn(name = "id")
    private User user;

    public LoginSecurity(User user) {
        this.user = user;
    }
}
