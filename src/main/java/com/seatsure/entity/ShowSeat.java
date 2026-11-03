package com.seatsure.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A seat's state for one show: the row that concurrent requests lock and compete for.
 * AVAILABLE -> HELD (by a hold) -> BOOKED (by a booking), and back to AVAILABLE on expiry or cancellation.
 */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "show_seats")
public class ShowSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "show_id", nullable = false)
    private Show show;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seat_id", nullable = false)
    private Seat seat;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ShowSeatStatus status = ShowSeatStatus.AVAILABLE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hold_id")
    private SeatHold hold;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id")
    private Booking booking;

    public void hold(SeatHold hold) {
        this.status = ShowSeatStatus.HELD;
        this.hold = hold;
        this.booking = null;
    }

    public void book(Booking booking) {
        this.status = ShowSeatStatus.BOOKED;
        this.booking = booking;
        this.hold = null;
    }

    public void release() {
        this.status = ShowSeatStatus.AVAILABLE;
        this.booking = null;
        this.hold = null;
    }
}
