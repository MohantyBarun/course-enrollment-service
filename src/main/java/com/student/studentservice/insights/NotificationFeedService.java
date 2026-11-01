package com.student.studentservice.insights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.student.studentservice.entity.NotificationLog;
import com.student.studentservice.entity.OutboxEvent;
import com.student.studentservice.notification.EmailMessage;
import com.student.studentservice.notification.EmailSender;
import com.student.studentservice.outbox.OutboxEvents;
import com.student.studentservice.repository.NotificationLogRepository;
import com.student.studentservice.repository.OutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The admin "Emails" page: every notification the outbox produced, with where it is in the
 * pipeline (queued, retrying, with the broker, sent, logged only, failed).
 */
@Slf4j
@Service
public class NotificationFeedService {

    public enum Status { QUEUED, RETRYING, IN_BROKER, SENT, LOGGED, PROCESSED, FAILED }

    public record Entry(UUID id, String eventType, String label, String recipient, String subject, String preview,
                        Status status, String detail, int attempts, Instant createdAt,
                        Instant handledAt, Instant nextAttemptAt) {
    }

    /** allowedRecipients: masked; empty when real email isn't restricted to an allowlist. */
    public record Feed(String emailMode, String transport, List<String> allowedRecipients, List<Entry> entries) {
    }

    private final OutboxEventRepository outboxRepository;
    private final NotificationLogRepository notificationLog;
    private final ObjectMapper objectMapper;
    private final EmailSender emailSender;
    private final boolean rabbitEnabled;

    public NotificationFeedService(OutboxEventRepository outboxRepository, NotificationLogRepository notificationLog,
                                   ObjectMapper objectMapper, EmailSender emailSender,
                                   @Value("${app.messaging.rabbit.enabled:false}") boolean rabbitEnabled) {
        this.outboxRepository = outboxRepository;
        this.notificationLog = notificationLog;
        this.objectMapper = objectMapper;
        this.emailSender = emailSender;
        this.rabbitEnabled = rabbitEnabled;
    }

    @Transactional(readOnly = true)
    public Feed latest() {
        List<OutboxEvent> events = outboxRepository.findTop50ByOrderByCreatedAtDesc();
        Map<UUID, NotificationLog> handled = notificationLog
                .findByMessageIdIn(events.stream().map(OutboxEvent::getId).toList()).stream()
                .collect(Collectors.toMap(NotificationLog::getMessageId, Function.identity()));
        List<Entry> entries = events.stream().map(e -> toEntry(e, handled.get(e.getId()))).toList();
        List<String> allowlist = emailSender.allowlist().stream().map(EmailMessage::mask).toList();
        return new Feed(emailSender.mode(), rabbitEnabled ? "RabbitMQ" : "In-process", allowlist, entries);
    }

    Entry toEntry(OutboxEvent event, NotificationLog log) {
        String label = label(event.getEventType());
        if (log != null) {
            return new Entry(event.getId(), event.getEventType(), label, log.getRecipient(), log.getSubject(),
                    log.getBodyPreview(), log.getDelivery() == NotificationLog.Delivery.SENT ? Status.SENT : Status.LOGGED,
                    log.getDetail(), event.getAttempts(), instant(event.getCreatedAt()), instant(log.getCreatedAt()), null);
        }
        Status status;
        String detail;
        switch (event.getStatus()) {
            case FAILED -> {
                status = Status.FAILED;
                detail = "Gave up after " + event.getAttempts() + " attempts: " + event.getLastError();
            }
            case PUBLISHED -> {
                if (rabbitEnabled) {
                    status = Status.IN_BROKER;
                    detail = "Published to RabbitMQ; the consumer is processing or retrying it, "
                            + "or it was dead-lettered after its retries";
                } else {
                    // In-process, "published" means the consumer already ran: this event predates delivery records
                    status = Status.PROCESSED;
                    detail = "Processed before delivery records were kept, so whether it was sent isn't known";
                }
            }
            default -> {
                status = event.getAttempts() > 0 ? Status.RETRYING : Status.QUEUED;
                detail = event.getAttempts() > 0 ? "Attempt " + event.getAttempts() + " failed: " + event.getLastError()
                        : "Waiting for the outbox relay";
            }
        }
        return new Entry(event.getId(), event.getEventType(), label, recipientOf(event), label, null, status, detail,
                event.getAttempts(), instant(event.getCreatedAt()), instant(event.getPublishedAt()),
                status == Status.RETRYING ? instant(event.getNextAttemptAt()) : null);
    }

    /** Stored timestamps are server-local; send instants so browsers in any time zone show the right time. */
    private static Instant instant(LocalDateTime time) {
        return time == null ? null : time.atZone(ZoneId.systemDefault()).toInstant();
    }

    private String recipientOf(OutboxEvent event) {
        try {
            JsonNode email = objectMapper.readTree(event.getPayload()).get("email");
            return email == null ? null : email.asText();
        } catch (Exception e) {
            return null;
        }
    }

    static String label(String eventType) {
        return switch (eventType) {
            case OutboxEvents.STUDENT_REGISTERED -> "Welcome email with temporary password";
            case OutboxEvents.ENROLLMENT_CONFIRMED -> "Enrollment confirmation";
            case OutboxEvents.TEST_EMAIL -> "Test email";
            default -> eventType;
        };
    }

    @Transactional
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Kolkata")
    public void purgeOld() {
        int deleted = notificationLog.deleteCreatedBefore(LocalDateTime.now().minusDays(30));
        log.info("Purged {} notification log rows older than 30 days", deleted);
    }
}
