package com.seatsure.insights;

import java.time.Clock;
import java.time.Instant;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsure.entity.NotificationLog;
import com.seatsure.entity.OutboxEvent;
import com.seatsure.insights.NotificationFeedService.Status;
import com.seatsure.notification.EmailSender;
import com.seatsure.outbox.OutboxEvents;
import com.seatsure.repository.NotificationLogRepository;
import com.seatsure.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationFeedServiceTest {

    @Mock private OutboxEventRepository outboxRepository;
    @Mock private NotificationLogRepository notificationLog;
    @Mock private EmailSender emailSender;

    private NotificationFeedService service;

    @BeforeEach
    void setUp() {
        service = new NotificationFeedService(outboxRepository, notificationLog, new ObjectMapper(), emailSender, false, Clock.systemUTC());
    }

    private static OutboxEvent event(String email) {
        return OutboxEvent.pending("User", "1", OutboxEvents.USER_REGISTERED,
                "{\"userId\":1,\"name\":\"Asha\",\"email\":\"" + email + "\"}",
                Instant.now());
    }

    private static NotificationLog logFor(OutboxEvent e, NotificationLog.Delivery delivery) {
        NotificationLog log = new NotificationLog();
        log.setMessageId(e.getId());
        log.setEventType(e.getEventType());
        log.setRecipient("asha@x.com");
        log.setSubject("Welcome to SeatSure");
        log.setBodyPreview("Hi Asha, your SeatSure account is ready.");
        log.setDelivery(delivery);
        log.setDetail(delivery == NotificationLog.Delivery.SENT ? "Accepted by the SMTP server" : "Not sent: demo mode");
        log.setCreatedAt(Instant.now());
        return log;
    }

    @Test
    void combinesOutboxStateWithDeliveryRecords() {
        OutboxEvent sent = event("asha@x.com");
        sent.markPublished(Instant.now());
        OutboxEvent logged = event("asha@x.com");
        logged.markPublished(Instant.now());
        OutboxEvent queued = event("queued@x.com");
        OutboxEvent retrying = event("retry@x.com");
        retrying.markFailed("SMTP timeout", Instant.now(), 10);
        OutboxEvent failed = event("dead@x.com");
        failed.setAttempts(9);
        failed.markFailed("SMTP 550", Instant.now(), 10);
        when(outboxRepository.findTop50ByOrderByCreatedAtDesc()).thenReturn(List.of(sent, logged, queued, retrying, failed));
        when(notificationLog.findByMessageIdIn(anyCollection()))
                .thenReturn(List.of(logFor(sent, NotificationLog.Delivery.SENT), logFor(logged, NotificationLog.Delivery.SKIPPED)));
        when(emailSender.mode()).thenReturn("Logged only (demo mode)");

        NotificationFeedService.Feed feed = service.latest();

        assertThat(feed.emailMode()).isEqualTo("Logged only (demo mode)");
        assertThat(feed.transport()).isEqualTo("In-process");
        assertThat(feed.entries()).extracting(NotificationFeedService.Entry::status)
                .containsExactly(Status.SENT, Status.LOGGED, Status.QUEUED, Status.RETRYING, Status.FAILED);
        assertThat(feed.entries().get(0).preview()).as("from the delivery record").contains("your SeatSure account is ready");
        assertThat(feed.entries().get(2).recipient()).as("taken from the payload before delivery").isEqualTo("queued@x.com");
        assertThat(feed.entries().get(3).detail()).contains("SMTP timeout");
        assertThat(feed.entries().get(3).nextAttemptAt()).isNotNull();
        assertThat(feed.entries().get(4).detail()).contains("Gave up after 10 attempts");
        assertThat(feed.entries().get(2).label()).isEqualTo("Welcome email");
    }

    @Test
    void inProcess_publishedWithoutARecord_predatesTracking_andTheAllowlistIsMasked() {
        OutboxEvent old = event("asha@x.com");
        old.markPublished(Instant.now());
        when(outboxRepository.findTop50ByOrderByCreatedAtDesc()).thenReturn(List.of(old));
        when(notificationLog.findByMessageIdIn(anyCollection())).thenReturn(List.of());
        when(emailSender.mode()).thenReturn("SMTP for 2 allowlisted recipients; others logged only");
        when(emailSender.allowlist()).thenReturn(List.of("owner@gmail.com", "@college.edu"));

        NotificationFeedService.Feed feed = service.latest();

        assertThat(feed.entries().get(0).status()).isEqualTo(Status.PROCESSED);
        assertThat(feed.allowedRecipients()).containsExactly("ow***@gmail.com", "@college.edu");
    }

    @Test
    void publishedWithoutARecord_isWithTheBroker() {
        OutboxEvent published = event("asha@x.com");
        published.markPublished(Instant.now());
        when(outboxRepository.findTop50ByOrderByCreatedAtDesc()).thenReturn(List.of(published));
        when(notificationLog.findByMessageIdIn(anyCollection())).thenReturn(List.of());
        when(emailSender.mode()).thenReturn("SMTP");

        assertThat(new NotificationFeedService(outboxRepository, notificationLog, new ObjectMapper(), emailSender, true, Clock.systemUTC())
                .latest().entries().get(0).status()).isEqualTo(Status.IN_BROKER);
    }
}
