package com.seatsure.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Seats set aside for one customer for a few minutes while they pay. A hold is live only while
 * it's ACTIVE and before {@code expiresAt}; a payment that arrives later is refunded.
 */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "seat_holds")
public class SeatHold implements OwnedByUser {

    public enum Status { ACTIVE, CONVERTED, EXPIRED, RELEASED }

    /** WAITLIST: created by the system for the next customer in the queue. */
    public enum Source { CUSTOMER, WAITLIST }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "show_id", nullable = false)
    private Show show;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private Source source;

    @Column(name = "seat_count", nullable = false)
    private int seatCount;

    @Column(name = "tickets_paise", nullable = false)
    private int ticketsPaise;

    @Column(name = "convenience_fee_paise", nullable = false)
    private int convenienceFeePaise;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id")
    private Booking booking;

    public SeatHold(Show show, User user, Source source, Instant now, Instant expiresAt) {
        this.show = show;
        this.user = user;
        this.source = source;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }

    public int totalPaise() {
        return ticketsPaise + convenienceFeePaise;
    }

    public boolean isLive(Instant now) {
        return status == Status.ACTIVE && now.isBefore(expiresAt);
    }

    public void close(Status status, Instant now) {
        this.status = status;
        this.closedAt = now;
    }

    /** The price at the moment the seats were held; the customer pays exactly this. */
    public void price(int seatCount, int ticketsPaise, int convenienceFeePaise) {
        this.seatCount = seatCount;
        this.ticketsPaise = ticketsPaise;
        this.convenienceFeePaise = convenienceFeePaise;
    }

    /** Paid in time: the seats became this booking. */
    public void convertTo(Booking booking, Instant now) {
        this.booking = booking;
        close(Status.CONVERTED, now);
    }
}
