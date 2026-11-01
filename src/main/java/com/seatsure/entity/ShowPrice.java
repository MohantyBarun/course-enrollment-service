package com.seatsure.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Ticket price for one seat category in one show, in paise. */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "show_prices")
public class ShowPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "show_id", nullable = false)
    private Show show;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    private SeatCategory category;

    @Column(name = "price_paise", nullable = false)
    private int pricePaise;

    public ShowPrice(Show show, SeatCategory category, int pricePaise) {
        this.show = show;
        this.category = category;
        this.pricePaise = pricePaise;
    }
}
