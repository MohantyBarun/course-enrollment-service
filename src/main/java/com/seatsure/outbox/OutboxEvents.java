package com.seatsure.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Event types (also the RabbitMQ routing keys) and their payloads. Amounts are in paise. */
public final class OutboxEvents {

    public static final String USER_REGISTERED = "user.registered";
    public static final String BOOKING_CONFIRMED = "booking.confirmed";
    public static final String BOOKING_CANCELLED = "booking.cancelled";
    public static final String SEATS_OFFERED = "waitlist.seats-offered";
    public static final String PAYMENT_REFUNDED = "payment.refunded";
    public static final String TEST_EMAIL = "notification.test";

    private OutboxEvents() {
    }

    public record UserRegistered(Long userId, String name, String email) {
    }

    public record BookingConfirmed(String reference, String name, String email, String movieTitle, String cinemaName,
                                   String screenName, Instant startsAt, String format, List<String> seats,
                                   int totalPaise) {
    }

    public record BookingCancelled(String reference, String name, String email, String movieTitle, Instant startsAt,
                                   int refundPaise, String reason) {
    }

    /** Seats that came free were held for a waitlisted customer, who has until expiresAt to pay. */
    public record SeatsOffered(Long holdId, String name, String email, String movieTitle, Instant startsAt,
                               List<String> seats, int totalPaise, Instant expiresAt) {
    }

    /** A payment arrived after its hold had lapsed (or the show was cancelled): the money goes back. */
    public record PaymentRefunded(String gatewayRef, String name, String email, String movieTitle, int amountPaise,
                                  String reason) {
    }

    /** An admin checking the email setup end to end (SMTP credentials, sender, allowlist). */
    public record TestEmailRequested(String email, String requestedBy) {
    }

    /** What a transport carries: the outbox row id doubles as the message id for consumer de-duplication. */
    public record Message(UUID id, String type, String payload) {
    }
}
