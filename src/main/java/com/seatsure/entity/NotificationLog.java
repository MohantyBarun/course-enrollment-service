package com.seatsure.entity;

import lombok.AccessLevel;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** What happened to each notification (sent, or only logged, and why): shown on the admin "Emails" page. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "notification_log")
public class NotificationLog {

    public enum Delivery { SENT, SKIPPED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The outbox event (and message) id. */
    @Column(name = "message_id", nullable = false, unique = true)
    private UUID messageId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "recipient", nullable = false, length = 150)
    private String recipient;

    @Column(name = "subject", nullable = false, length = 200)
    private String subject;

    @Column(name = "body_preview", nullable = false, length = 2000)
    private String bodyPreview;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery", nullable = false, length = 20)
    private Delivery delivery;

    @Column(name = "detail", length = 300)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    private static final int MAX_PREVIEW = 2000;

    public NotificationLog(UUID messageId, String eventType, String recipient, String subject, String body,
                           Delivery delivery, String detail, Instant createdAt) {
        this.messageId = messageId;
        this.eventType = eventType;
        this.recipient = recipient;
        this.subject = subject;
        this.bodyPreview = body.length() > MAX_PREVIEW ? body.substring(0, MAX_PREVIEW) : body;
        this.delivery = delivery;
        this.detail = detail;
        this.createdAt = createdAt;
    }
}
