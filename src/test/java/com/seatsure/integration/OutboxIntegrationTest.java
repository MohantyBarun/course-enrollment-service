package com.seatsure.integration;

import com.seatsure.dto.AuthDtos.RegisterRequest;
import com.seatsure.insights.NotificationFeedService;
import com.seatsure.notification.EmailMessage;
import com.seatsure.notification.EmailSender;
import com.seatsure.outbox.OutboxEvents;
import com.seatsure.repository.OutboxEventRepository;
import com.seatsure.repository.UserRepository;
import com.seatsure.service.AuthService;
import com.seatsure.service.BookingService;
import com.seatsure.support.FullAppIntegrationTest;
import com.seatsure.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/** The transactional outbox with the in-process transport, against PostgreSQL. */
@FullAppIntegrationTest
class OutboxIntegrationTest {

    @Autowired private AuthService authService;
    @Autowired private BookingService bookingService;
    @Autowired private TestFixtures fixtures;
    @Autowired private UserRepository users;
    @Autowired private OutboxEventRepository outboxRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private NotificationFeedService notificationFeed;

    @MockitoBean private EmailSender emailSender;

    @BeforeEach
    void smtpAcceptsEverything() {
        when(emailSender.send(any())).thenReturn(EmailSender.Delivery.sent("Accepted by the SMTP server"));
        when(emailSender.mode()).thenReturn("SMTP");
    }

    @Test
    @DisplayName("sign-up, booking and cancellation each send one email, after their transaction commits")
    void outbox_deliversEmailsAfterCommit() {
        String email = TestFixtures.unique("outbox") + "@seatsure.test";
        Long userId = authService.register(new RegisterRequest("Outbox Tester", email, null, TestFixtures.PASSWORD))
                .user().id();
        TestFixtures.ShowFixture show = fixtures.show(1, 4);
        var booking = bookingService.book(userId, show.showId(), show.seatIds().subList(0, 2));
        bookingService.cancel(userId, booking.reference(), "Plans changed");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            verify(emailSender, times(1)).send(argThat((EmailMessage m) ->
                    m.to().equals(email) && m.subject().equals("Welcome to SeatSure")));
            verify(emailSender, times(1)).send(argThat((EmailMessage m) ->
                    m.to().equals(email) && m.subject().startsWith("Tickets confirmed: ")
                            && m.body().contains("Booking ID: " + booking.reference())
                            && m.body().contains("Seats: A1, A2")));
            verify(emailSender, times(1)).send(argThat((EmailMessage m) ->
                    m.to().equals(email) && m.subject().startsWith("Booking cancelled: ")
                            && m.body().contains("Plans changed")));
        });

        Long notPublished = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE status <> 'PUBLISHED' "
                + "AND payload LIKE ?", Long.class, "%" + email + "%");
        assertThat(notPublished).isZero();
        var entries = notificationFeed.latest().entries().stream().filter(e -> email.equals(e.recipient())).toList();
        assertThat(entries).hasSize(3)
                .allSatisfy(e -> assertThat(e.status()).isEqualTo(NotificationFeedService.Status.SENT));
        assertThat(entries).extracting(NotificationFeedService.Entry::eventType).containsExactlyInAnyOrder(
                OutboxEvents.USER_REGISTERED, OutboxEvents.BOOKING_CONFIRMED, OutboxEvents.BOOKING_CANCELLED);
    }

    @Test
    @DisplayName("a rolled-back sign-up leaves no outbox event, so no email is ever sent")
    void rolledBackChange_hasNoEvent() {
        String email = TestFixtures.unique("rollback") + "@seatsure.test";
        long before = outboxRepository.count();

        transactionTemplate.executeWithoutResult(tx -> {
            authService.register(new RegisterRequest("Rolled Back", email, null, TestFixtures.PASSWORD));
            tx.setRollbackOnly();
        });

        assertThat(outboxRepository.count()).isEqualTo(before);
        assertThat(users.existsByEmail(email)).isFalse();
        verify(emailSender, after(2000).never()).send(argThat((EmailMessage m) -> m.to().equals(email)));
    }
}
