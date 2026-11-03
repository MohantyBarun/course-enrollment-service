package com.seatsure.notification;

import java.time.Clock;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsure.entity.NotificationLog;
import com.seatsure.outbox.OutboxEvents;
import com.seatsure.outbox.OutboxEvents.BookingCancelled;
import com.seatsure.outbox.OutboxEvents.BookingConfirmed;
import com.seatsure.outbox.OutboxEvents.TestEmailRequested;
import com.seatsure.outbox.OutboxEvents.UserRegistered;
import com.seatsure.repository.NotificationLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Consumes outbox events (from RabbitMQ or in-process), sends the matching email and records the outcome. */
@Slf4j
@Service
public class NotificationDispatcher {

    private static final DateTimeFormatter SHOW_TIME =
            DateTimeFormatter.ofPattern("EEE d MMM yyyy, h:mm a", Locale.ENGLISH);

    private final ProcessedMessages processedMessages;
    private final EmailSender emailSender;
    private final ObjectMapper objectMapper;
    private final NotificationLogRepository notificationLog;
    private final String appUrl;
    private final ZoneId zone;
    private final Clock clock;

    public NotificationDispatcher(ProcessedMessages processedMessages, EmailSender emailSender,
                                  ObjectMapper objectMapper, NotificationLogRepository notificationLog,
                                  @Value("${app.mail.app-url:}") String appUrl, ZoneId audienceZone, Clock clock) {
        this.processedMessages = processedMessages;
        this.emailSender = emailSender;
        this.objectMapper = objectMapper;
        this.notificationLog = notificationLog;
        this.appUrl = appUrl;
        this.zone = audienceZone;
        this.clock = clock;
    }

    /**
     * REQUIRES_NEW: the de-duplication record and the log row commit only if the email was handled.
     * If sending throws, both roll back and the message is retried; the caller's transaction is unaffected.
     * (If the commit itself fails after SMTP accepted the email, the retry sends it again: SMTP isn't
     * transactional, so delivery is at-least-once.)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dispatch(OutboxEvents.Message message) {
        if (!processedMessages.markProcessed(message.id())) {
            log.info("Message {} ({}) already processed; skipping duplicate", message.id(), message.type());
            return;
        }
        EmailMessage email = switch (message.type()) {
            case OutboxEvents.USER_REGISTERED -> welcomeEmail(read(message, UserRegistered.class));
            case OutboxEvents.BOOKING_CONFIRMED -> ticketEmail(read(message, BookingConfirmed.class));
            case OutboxEvents.BOOKING_CANCELLED -> cancellationEmail(read(message, BookingCancelled.class));
            case OutboxEvents.SEATS_OFFERED -> seatsOfferedEmail(read(message, OutboxEvents.SeatsOffered.class));
            case OutboxEvents.PAYMENT_REFUNDED -> refundEmail(read(message, OutboxEvents.PaymentRefunded.class));
            case OutboxEvents.TEST_EMAIL -> testEmail(read(message, TestEmailRequested.class));
            default -> null;
        };
        if (email == null) {
            log.warn("No notification for event type {} (message {})", message.type(), message.id());
            return;
        }
        EmailSender.Delivery delivery = emailSender.send(email);
        record(message, email, delivery);
    }

    private void record(OutboxEvents.Message message, EmailMessage email, EmailSender.Delivery delivery) {
        NotificationLog entry = new NotificationLog();
        entry.setMessageId(message.id());
        entry.setEventType(message.type());
        entry.setRecipient(email.to());
        entry.setSubject(email.subject());
        String body = email.body();
        entry.setBodyPreview(body.length() > 2000 ? body.substring(0, 2000) : body);
        entry.setDelivery(delivery.sent() ? NotificationLog.Delivery.SENT : NotificationLog.Delivery.SKIPPED);
        entry.setDetail(delivery.detail());
        entry.setCreatedAt(clock.instant());
        notificationLog.save(entry);
    }

    EmailMessage welcomeEmail(UserRegistered event) {
        String body = "Hi " + event.name() + ",\n\n"
                + "Welcome to SeatSure! Your account is ready. Pick a movie, choose your seats, "
                + "and the seats you hold are guaranteed to be yours."
                + link("Browse movies", "")
                + "\n\nTeam SeatSure";
        return new EmailMessage(event.email(), "Welcome to SeatSure", body);
    }

    EmailMessage ticketEmail(BookingConfirmed event) {
        String body = "Hi " + event.name() + ",\n\n"
                + "Your tickets are confirmed.\n\n"
                + event.movieTitle() + " (" + event.format() + ")\n"
                + event.cinemaName() + ", " + event.screenName() + "\n"
                + showTime(event.startsAt()) + "\n"
                + "Seats: " + String.join(", ", event.seats()) + "\n"
                + "Amount paid: " + rupees(event.totalPaise()) + "\n"
                + "Booking ID: " + event.reference() + "\n\n"
                + "Show this booking ID at the cinema. Please arrive 15 minutes early."
                + link("View your ticket", "/bookings/" + event.reference())
                + "\n\nTeam SeatSure";
        return new EmailMessage(event.email(),
                "Tickets confirmed: " + event.movieTitle() + " · " + showTime(event.startsAt()), body);
    }

    EmailMessage cancellationEmail(BookingCancelled event) {
        String body = "Hi " + event.name() + ",\n\n"
                + "Your booking " + event.reference() + " for " + event.movieTitle() + " on "
                + showTime(event.startsAt()) + " has been cancelled.\n"
                + "Reason: " + event.reason() + "\n\n"
                + "A refund of " + rupees(event.refundPaise()) + " will reach your original payment method "
                + "in 5-7 working days."
                + "\n\nTeam SeatSure";
        return new EmailMessage(event.email(), "Booking cancelled: " + event.movieTitle(), body);
    }

    EmailMessage seatsOfferedEmail(OutboxEvents.SeatsOffered event) {
        String body = "Hi " + event.name() + ",\n\n"
                + "Good news: seats opened up for " + event.movieTitle() + " on " + showTime(event.startsAt())
                + ", and you were next on the waitlist.\n\n"
                + "We're holding " + String.join(", ", event.seats()) + " for you (" + rupees(event.totalPaise())
                + "). Pay by " + SHOW_TIME.format(event.expiresAt().atZone(zone))
                + " to confirm them; after that they go to the next person in line."
                + link("Complete your booking", "/checkout/" + event.holdId())
                + "\n\nTeam SeatSure";
        return new EmailMessage(event.email(), "Seats held for you: " + event.movieTitle(), body);
    }

    EmailMessage refundEmail(OutboxEvents.PaymentRefunded event) {
        String body = "Hi " + event.name() + ",\n\n"
                + "We received your payment of " + rupees(event.amountPaise()) + " for " + event.movieTitle()
                + ", but we couldn't confirm the seats: " + event.reason() + ".\n\n"
                + "The full amount has been refunded (reference " + event.gatewayRef() + ") and will reach your "
                + "original payment method in 5-7 working days."
                + "\n\nTeam SeatSure";
        return new EmailMessage(event.email(), "Payment refunded: " + event.movieTitle(), body);
    }

    EmailMessage testEmail(TestEmailRequested event) {
        String body = "Hi,\n\n"
                + "This is a test email from SeatSure, requested by " + event.requestedBy() + ".\n\n"
                + "It went through the same path as every notification: the transactional outbox, the relay, "
                + "the idempotent consumer and the SMTP sender. If you're reading this, the email setup works."
                + "\n\nTeam SeatSure";
        return new EmailMessage(event.email(), "SeatSure test email", body);
    }

    String showTime(Instant startsAt) {
        return SHOW_TIME.format(startsAt.atZone(zone));
    }

    static String rupees(int paise) {
        return paise % 100 == 0 ? "₹" + paise / 100 : String.format(Locale.ENGLISH, "₹%d.%02d", paise / 100, paise % 100);
    }

    private String link(String label, String path) {
        return appUrl.isBlank() ? "" : "\n\n" + label + ": " + appUrl.replaceAll("/+$", "") + path;
    }

    private <T> T read(OutboxEvents.Message message, Class<T> type) {
        try {
            return objectMapper.readValue(message.payload(), type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Malformed " + message.type() + " payload in message " + message.id(), e);
        }
    }
}
