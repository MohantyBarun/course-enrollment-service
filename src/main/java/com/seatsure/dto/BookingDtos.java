package com.seatsure.dto;

import com.seatsure.entity.Booking;
import com.seatsure.entity.BookingStatus;
import com.seatsure.entity.SeatCategory;
import com.seatsure.entity.Show;
import com.seatsure.entity.ShowFormat;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class BookingDtos {

    private BookingDtos() {
    }

    public record CancelRequest(@Size(max = 200) String reason) {
    }

    public record SeatLine(String label, SeatCategory category, int pricePaise) {
    }

    public record BookingView(String reference, BookingStatus status, Long showId, Long movieId, String movieTitle,
                              String posterUrl, String cinemaName, String cityName, String screenName,
                              Instant startsAt, ShowFormat format, List<SeatLine> seats, int ticketsPaise,
                              int convenienceFeePaise, int totalPaise, Instant bookedAt, Instant cancelledAt,
                              String cancelReason, boolean cancellable) {

        public static BookingView of(Booking b, boolean cancellable) {
            Show s = b.getShow();
            return new BookingView(b.getReference(), b.getStatus(), s.getId(), s.getMovie().getId(),
                    s.getMovie().getTitle(), s.getMovie().getPosterUrl(), s.getScreen().getCinema().getName(),
                    s.getScreen().getCinema().getCity().getName(), s.getScreen().getName(), s.getStartsAt(),
                    s.getFormat(),
                    b.getSeats().stream().map(x -> new SeatLine(x.getSeatLabel(), x.getCategory(), x.getPricePaise()))
                            .toList(),
                    b.getTicketsPaise(), b.getConvenienceFeePaise(), b.totalPaise(), b.getCreatedAt(),
                    b.getCancelledAt(), b.getCancelReason(), cancellable);
        }
    }
}
