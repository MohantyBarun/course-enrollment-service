package com.student.studentservice.entity;

import com.student.studentservice.service.AccountStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "login_security")
public class LoginSecurity {

    @Id
    private Long id;

    @Column(name = "wrong_password_count", nullable = false)
    private Integer wrongPasswordCount=0;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_status", nullable = false)
    private AccountStatus accountStatus= AccountStatus.ACTIVE;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "last_failed_at")
    private LocalDateTime lastFailedAt;

    @Column(name = "blocked_at")
    private LocalDateTime blockedAt;

    @Column(name = "unblocked_at")
    private LocalDateTime unblockedAt;

    @Column(name = "block_reason")
    private String blockReason;

    @Column(name = "block_expiry_at")
    private LocalDateTime blockExpiryAt;

    @Column(name = "permanent_block", nullable = false)
    private Boolean permanentBlock = false;

    @ToString.Exclude
    @OneToOne
    @MapsId
    @JoinColumn(name = "id")
    private User user;


}
