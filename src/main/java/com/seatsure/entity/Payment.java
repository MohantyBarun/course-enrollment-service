package com.seatsure.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** One attempt to pay for a hold. Only PENDING moves (to SUCCEEDED or FAILED); SUCCEEDED can become REFUNDED. */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "payments")
public class Payment implements OwnedByUser {

    public enum Status { PENDING, SUCCEEDED, FAILED, REFUNDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hold_id", nullable = false)
    private SeatHold hold;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "gateway_ref", nullable = false, unique = true, length = 40)
    private String gatewayRef;

    @Column(name = "amount_paise", nullable = false)
    private int amountPaise;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "failure_reason", length = 300)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Payment(SeatHold hold, User user, String gatewayRef, int amountPaise, Instant now) {
        this.hold = hold;
        this.user = user;
        this.gatewayRef = gatewayRef;
        this.amountPaise = amountPaise;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void moveTo(Status next, String reason, Instant now) {
        this.status = next;
        this.failureReason = reason;
        this.updatedAt = now;
    }
}
