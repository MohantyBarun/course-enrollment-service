package com.seatsure.dto;

import com.seatsure.entity.Payment;
import com.seatsure.entity.SeatCategory;
import com.seatsure.entity.SeatHold;
import com.seatsure.entity.ShowFormat;
import com.seatsure.entity.WaitlistEntry;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** Holding seats, paying for them, and the waitlist. Amounts are in paise. */
public final class CheckoutDtos {

    private CheckoutDtos() {
    }

    public static final int MAX_SEATS = 10;

    public record HoldRequest(
            @NotEmpty(message = "Pick at least one seat")
            @Size(max = MAX_SEATS, message = "You can book up to " + MAX_SEATS + " seats at a time")
            List<@NotNull Long> seatIds) {
    }

    public record HeldSeat(Long seatId, String label, SeatCategory category, int pricePaise) {
    }

    public record PaymentSummary(String gatewayRef, Payment.Status status, String failureReason, Instant updatedAt) {
        public static PaymentSummary of(Payment p) {
            return p == null ? null : new PaymentSummary(p.getGatewayRef(), p.getStatus(), p.getFailureReason(),
                    p.getUpdatedAt());
        }
    }

    /**
     * A hold and where it is in checkout. The client polls this after paying: the booking reference
     * appears once the payment webhook has been processed.
     */
    public record HoldView(Long holdId, SeatHold.Status status, SeatHold.Source source, Long showId, String movieTitle,
                           String cinemaName, String screenName, Instant startsAt, ShowFormat format,
                           List<HeldSeat> seats, int ticketsPaise, int convenienceFeePaise, int totalPaise,
                           Instant expiresAt, PaymentSummary latestPayment, String bookingReference) {
    }

    /** What the client needs to send the customer to the (mock) payment page. */
    public record PaymentStarted(String gatewayRef, int amountPaise, Payment.Status status, String checkoutPath) {
    }

    public enum MockOutcome { SUCCESS, FAILURE }

    public record MockCheckoutRequest(@NotNull MockOutcome outcome) {
    }

    public record WaitlistRequest(@Min(1) @Max(6) int seats) {
    }

    public record WaitlistView(Long id, Long showId, String movieTitle, String cinemaName, Instant startsAt,
                               int seatsWanted, WaitlistEntry.Status status, Long positionInQueue, Long holdId,
                               Instant joinedAt) {
    }
}
