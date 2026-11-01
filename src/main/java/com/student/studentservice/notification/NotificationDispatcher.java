package com.student.studentservice.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.student.studentservice.entity.NotificationLog;
import com.student.studentservice.outbox.OutboxEvents;
import com.student.studentservice.outbox.OutboxEvents.EnrollmentConfirmed;
import com.student.studentservice.outbox.OutboxEvents.StudentRegistered;
import com.student.studentservice.outbox.OutboxEvents.TestEmailRequested;
import com.student.studentservice.repository.NotificationLogRepository;
import com.student.studentservice.util.SecretBox;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** Consumes outbox events (from RabbitMQ or in-process), sends the matching email and records the outcome. */
@Slf4j
@Service
public class NotificationDispatcher {

    static final String MASK = "••••••••";

    /** An email, plus a copy of its body that's safe to store and show (the password masked). */
    record Prepared(EmailMessage email, String preview) {
    }

    private final ProcessedMessages processedMessages;
    private final EmailSender emailSender;
    private final ObjectMapper objectMapper;
    private final SecretBox secretBox;
    private final NotificationLogRepository notificationLog;
    private final String signInUrl;

    public NotificationDispatcher(ProcessedMessages processedMessages, EmailSender emailSender,
                                  ObjectMapper objectMapper, SecretBox secretBox,
                                  NotificationLogRepository notificationLog,
                                  @Value("${app.mail.sign-in-url:}") String signInUrl) {
        this.processedMessages = processedMessages;
        this.emailSender = emailSender;
        this.objectMapper = objectMapper;
        this.secretBox = secretBox;
        this.notificationLog = notificationLog;
        this.signInUrl = signInUrl;
    }

    /**
     * REQUIRES_NEW: the de-duplication record and the log row commit only if the email was handled.
     * If sending throws, both roll back and the message is retried; the caller's transaction is unaffected.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dispatch(OutboxEvents.Message message) {
        if (!processedMessages.markProcessed(message.id())) {
            log.info("Message {} ({}) already processed; skipping duplicate", message.id(), message.type());
            return;
        }
        Prepared prepared = switch (message.type()) {
            case OutboxEvents.STUDENT_REGISTERED -> welcomeEmail(read(message, StudentRegistered.class));
            case OutboxEvents.ENROLLMENT_CONFIRMED -> enrollmentEmail(read(message, EnrollmentConfirmed.class));
            case OutboxEvents.TEST_EMAIL -> testEmail(read(message, TestEmailRequested.class));
            default -> null;
        };
        if (prepared == null) {
            log.warn("No notification for event type {} (message {})", message.type(), message.id());
            return;
        }
        EmailSender.Delivery delivery = emailSender.send(prepared.email());
        record(message, prepared, delivery);
    }

    private void record(OutboxEvents.Message message, Prepared prepared, EmailSender.Delivery delivery) {
        NotificationLog entry = new NotificationLog();
        entry.setMessageId(message.id());
        entry.setEventType(message.type());
        entry.setRecipient(prepared.email().to());
        entry.setSubject(prepared.email().subject());
        String preview = prepared.preview();
        entry.setBodyPreview(preview.length() > 2000 ? preview.substring(0, 2000) : preview);
        entry.setDelivery(delivery.sent() ? NotificationLog.Delivery.SENT : NotificationLog.Delivery.SKIPPED);
        entry.setDetail(delivery.detail());
        entry.setCreatedAt(LocalDateTime.now());
        notificationLog.save(entry);
    }

    Prepared welcomeEmail(StudentRegistered event) {
        String password = secretBox.decrypt(event.encryptedTempPassword());
        return new Prepared(
                new EmailMessage(event.email(), "Your StudentHub account", welcomeBody(event, password)),
                welcomeBody(event, MASK));
    }

    private String welcomeBody(StudentRegistered event, String password) {
        return "Hi " + event.name() + ",\n\n"
                + "The academic office has created a StudentHub account for you.\n\n"
                + "Email: " + event.email() + "\n"
                + "Temporary password: " + password + "\n\n"
                + "The temporary password is valid for " + event.tempPasswordValidHours() + " hours. "
                + "You'll be asked to choose your own password the first time you sign in."
                + signInLine()
                + "\n\nAcademic Office";
    }

    Prepared enrollmentEmail(EnrollmentConfirmed event) {
        String body = "Hi " + event.name() + ",\n\n"
                + "You're enrolled in " + event.courseCode() + " " + event.courseTitle()
                + " (" + event.credits() + " credits, " + event.term() + ").\n\n"
                + "You can drop the course while enrollment is open."
                + signInLine()
                + "\n\nAcademic Office";
        return new Prepared(new EmailMessage(event.email(),
                "Enrolled: " + event.courseCode() + " " + event.courseTitle(), body), body);
    }

    Prepared testEmail(TestEmailRequested event) {
        String body = "Hi,\n\n"
                + "This is a test email from StudentHub, requested by " + event.requestedBy() + ".\n\n"
                + "It went through the same path as every notification: the transactional outbox, the relay, "
                + "the idempotent consumer and the SMTP sender. If you're reading this, the email setup works."
                + signInLine()
                + "\n\nStudentHub";
        return new Prepared(new EmailMessage(event.email(), "StudentHub test email", body), body);
    }

    private String signInLine() {
        return signInUrl.isBlank() ? "" : "\n\nSign in: " + signInUrl;
    }

    private <T> T read(OutboxEvents.Message message, Class<T> type) {
        try {
            return objectMapper.readValue(message.payload(), type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Malformed " + message.type() + " payload in message " + message.id(), e);
        }
    }
}
