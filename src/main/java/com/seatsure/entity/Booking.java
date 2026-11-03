package com.seatsure.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "bookings")
public class Booking implements OwnedByUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Shown on the ticket and used in URLs; not guessable, unlike the numeric id. */
    @Column(name = "reference", nullable = false, unique = true, length = 12)
    private String reference;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "show_id", nullable = false)
    private Show show;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BookingStatus status = BookingStatus.CONFIRMED;

    @Column(name = "seat_count", nullable = false)
    private int seatCount;

    @Column(name = "tickets_paise", nullable = false)
    private int ticketsPaise;

    @Column(name = "convenience_fee_paise", nullable = false)
    private int convenienceFeePaise;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancel_reason", length = 200)
    private String cancelReason;

    /** The payment that bought it (null for seeded demo bookings). */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id")
    private Payment payment;

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("seatLabel")
    private List<BookingSeat> seats = new ArrayList<>();

    public Booking(String reference, User user, Show show) {
        this.reference = reference;
        this.user = user;
        this.show = show;
    }

    public int totalPaise() {
        return ticketsPaise + convenienceFeePaise;
    }

    public void addSeat(Seat seat, int pricePaise) {
        seats.add(new BookingSeat(this, seat, pricePaise));
        seatCount = seats.size();
        ticketsPaise += pricePaise;
    }

    public void cancel(String reason, Instant now) {
        this.status = BookingStatus.CANCELLED;
        this.cancelledAt = now;
        this.cancelReason = reason;
    }
}
