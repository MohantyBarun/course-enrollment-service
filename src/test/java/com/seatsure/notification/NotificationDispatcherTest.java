package com.seatsure.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.seatsure.entity.NotificationLog;
import com.seatsure.outbox.OutboxEvents;
import com.seatsure.repository.NotificationLogRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationDispatcherTest {

    /** 14:30 UTC = 8:00 PM in Kolkata. */
    private static final Instant SHOW = Instant.parse("2026-11-07T14:30:00Z");

    @Mock private ProcessedMessages processedMessages;
    @Mock private EmailSender emailSender;
    @Mock private NotificationLogRepository notificationLog;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private NotificationDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new NotificationDispatcher(processedMessages, emailSender, objectMapper, notificationLog,
                "https://seatsure.example.com/", ZoneId.of("Asia/Kolkata"));
    }

    private OutboxEvents.Message message(String type, Object payload) throws Exception {
        return new OutboxEvents.Message(UUID.randomUUID(), type, objectMapper.writeValueAsString(payload));
    }

    private static OutboxEvents.BookingConfirmed confirmed() {
        return new OutboxEvents.BookingConfirmed("SS7K2M9QXA", "Asha", "asha@x.com", "Monsoon Protocol",
                "Starlight Cinemas: Patia", "Audi 1", SHOW, "2D", List.of("H7", "H8"), 50_000);
    }

    private EmailMessage sentEmail() {
        ArgumentCaptor<EmailMessage> email = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender).send(email.capture());
        return email.getValue();
    }

    private NotificationLog savedLog() {
        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLog).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void ticketEmail_showsLocalShowTimeSeatsAmountAndLink() throws Exception {
        var msg = message(OutboxEvents.BOOKING_CONFIRMED, confirmed());
        when(processedMessages.markProcessed(msg.id())).thenReturn(true);
        when(emailSender.send(any())).thenReturn(EmailSender.Delivery.sent("Accepted by the SMTP server"));

        dispatcher.dispatch(msg);

        EmailMessage email = sentEmail();
        assertThat(email.to()).isEqualTo("asha@x.com");
        assertThat(email.subject()).isEqualTo("Tickets confirmed: Monsoon Protocol · Sat 7 Nov 2026, 8:00 PM");
        assertThat(email.body())
                .contains("Seats: H7, H8")
                .contains("Amount paid: ₹500")
                .contains("Booking ID: SS7K2M9QXA")
                .contains("View your ticket: https://seatsure.example.com/bookings/SS7K2M9QXA");
        NotificationLog log = savedLog();
        assertThat(log.getMessageId()).isEqualTo(msg.id());
        assertThat(log.getDelivery()).isEqualTo(NotificationLog.Delivery.SENT);
    }

    @Test
    void cancellationEmail_mentionsReasonAndRefund() throws Exception {
        var msg = message(OutboxEvents.BOOKING_CANCELLED, new OutboxEvents.BookingCancelled("SS7K2M9QXA", "Asha",
                "asha@x.com", "Monsoon Protocol", SHOW, 52_550, "Show cancelled by the cinema: Projector failure"));
        when(processedMessages.markProcessed(msg.id())).thenReturn(true);
        when(emailSender.send(any())).thenReturn(EmailSender.Delivery.skipped("Logged only"));

        dispatcher.dispatch(msg);

        EmailMessage email = sentEmail();
        assertThat(email.subject()).isEqualTo("Booking cancelled: Monsoon Protocol");
        assertThat(email.body()).contains("Projector failure").contains("A refund of ₹525.50");
        assertThat(savedLog().getDelivery()).isEqualTo(NotificationLog.Delivery.SKIPPED);
    }

    @Test
    void welcomeEmail() throws Exception {
        var msg = message(OutboxEvents.USER_REGISTERED, new OutboxEvents.UserRegistered(7L, "Asha", "asha@x.com"));
        when(processedMessages.markProcessed(msg.id())).thenReturn(true);
        when(emailSender.send(any())).thenReturn(EmailSender.Delivery.sent("ok"));

        dispatcher.dispatch(msg);

        assertThat(sentEmail().subject()).isEqualTo("Welcome to SeatSure");
    }

    @Test
    void testEmail_goesToTheRequestedAddress() throws Exception {
        var msg = message(OutboxEvents.TEST_EMAIL, new OutboxEvents.TestEmailRequested("owner@gmail.com", "admin@x.com"));
        when(processedMessages.markProcessed(msg.id())).thenReturn(true);
        when(emailSender.send(any())).thenReturn(EmailSender.Delivery.sent("ok"));

        dispatcher.dispatch(msg);

        assertThat(sentEmail().subject()).isEqualTo("SeatSure test email");
        assertThat(savedLog().getEventType()).isEqualTo(OutboxEvents.TEST_EMAIL);
    }

    @Test
    void duplicateMessage_isSkipped() throws Exception {
        var msg = message(OutboxEvents.BOOKING_CONFIRMED, confirmed());
        when(processedMessages.markProcessed(msg.id())).thenReturn(false);

        dispatcher.dispatch(msg);

        verifyNoInteractions(emailSender, notificationLog);
    }

    @Test
    void unknownEventType_isIgnored() throws Exception {
        var msg = message("movie.renamed", java.util.Map.of("movieId", 1));
        when(processedMessages.markProcessed(msg.id())).thenReturn(true);

        dispatcher.dispatch(msg);

        verifyNoInteractions(emailSender, notificationLog);
    }

    @Test
    void sendFailure_propagatesSoTheMessageIsRetried_andNothingIsLogged() throws Exception {
        var msg = message(OutboxEvents.BOOKING_CONFIRMED, confirmed());
        when(processedMessages.markProcessed(msg.id())).thenReturn(true);
        doThrow(new IllegalStateException("SMTP down")).when(emailSender).send(any());

        assertThatThrownBy(() -> dispatcher.dispatch(msg)).hasMessageContaining("SMTP down");
        verifyNoInteractions(notificationLog);
    }

    @Test
    void rupees_formatsWholeAndFractionalAmounts() {
        assertThat(NotificationDispatcher.rupees(50_000)).isEqualTo("₹500");
        assertThat(NotificationDispatcher.rupees(52_550)).isEqualTo("₹525.50");
        assertThat(NotificationDispatcher.rupees(5)).isEqualTo("₹0.05");
    }

    @Test
    void emailAddressesAreMaskedForLogs() {
        assertThat(new EmailMessage("john.doe@x.com", "s", "b").maskedRecipient()).isEqualTo("jo***@x.com");
        assertThat(new EmailMessage("broken", "s", "b").maskedRecipient()).isEqualTo("***");
    }

    @Test
    void emailSenderSelection() {
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> none = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> smtp = mock(ObjectProvider.class);
        when(smtp.getIfAvailable()).thenReturn(mock(JavaMailSender.class));
        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.ofDefaults();
        NotificationConfig config = new NotificationConfig();

        assertThat(config.emailSender(none, "from@x.com", false, "", breakers)).isInstanceOf(LoggingEmailSender.class);
        assertThat(config.emailSender(none, "from@x.com", true, "me@x.com", breakers))
                .as("an allowlist doesn't help without SMTP").isInstanceOf(LoggingEmailSender.class);
        assertThat(config.emailSender(smtp, "from@x.com", false, "", breakers)).isInstanceOf(SmtpEmailSender.class);
        assertThat(config.emailSender(smtp, "from@x.com", true, "", breakers))
                .as("a public demo without an allowlist never sends real email").isInstanceOf(LoggingEmailSender.class);
        assertThat(config.emailSender(smtp, "from@x.com", true, "me@x.com", breakers))
                .isInstanceOf(AllowlistEmailSender.class);
    }
}
