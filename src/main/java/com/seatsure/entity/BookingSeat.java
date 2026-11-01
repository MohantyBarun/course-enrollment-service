package com.seatsure.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A seat as it was bought: label, category and price are copied so history never changes. */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "booking_seats")
public class BookingSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seat_id", nullable = false)
    private Seat seat;

    @Column(name = "seat_label", nullable = false, length = 8)
    private String seatLabel;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    private SeatCategory category;

    @Column(name = "price_paise", nullable = false)
    private int pricePaise;

    BookingSeat(Booking booking, Seat seat, int pricePaise) {
        this.booking = booking;
        this.seat = seat;
        this.seatLabel = seat.label();
        this.category = seat.getCategory();
        this.pricePaise = pricePaise;
    }
}
