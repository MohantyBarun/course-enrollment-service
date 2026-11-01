package com.seatsure.service;

import com.seatsure.entity.SeatCategory;
import com.seatsure.entity.ShowPrice;
import com.seatsure.entity.ShowSeat;
import com.seatsure.repository.ShowPriceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/** Ticket prices come from the show's price list; the convenience fee is per seat. Amounts in paise. */
@Component
public class Pricing {

    private final ShowPriceRepository prices;
    private final int feePerSeatPaise;

    public Pricing(ShowPriceRepository prices,
                   @Value("${app.booking.convenience-fee-paise:2500}") int feePerSeatPaise) {
        this.prices = prices;
        this.feePerSeatPaise = feePerSeatPaise;
    }

    public record Quote(Map<SeatCategory, Integer> priceByCategory, int ticketsPaise, int feePaise) {
        public int priceOf(ShowSeat seat) {
            return priceByCategory.get(seat.getSeat().getCategory());
        }
    }

    public Quote quote(Long showId, Collection<ShowSeat> seats) {
        Map<SeatCategory, Integer> byCategory = prices.findByShowId(showId).stream()
                .collect(Collectors.toMap(ShowPrice::getCategory, ShowPrice::getPricePaise));
        int tickets = 0;
        for (ShowSeat seat : seats) {
            Integer price = byCategory.get(seat.getSeat().getCategory());
            if (price == null) {
                throw new IllegalStateException("Show " + showId + " has no price for " + seat.getSeat().getCategory());
            }
            tickets += price;
        }
        return new Quote(byCategory, tickets, feePerSeatPaise * seats.size());
    }
}
