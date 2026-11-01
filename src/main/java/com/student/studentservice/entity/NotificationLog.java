package com.student.studentservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/** What happened to each notification: shown on the admin "Emails" page. Passwords are masked. */
@Entity
@Getter
@Setter
@NoArgsConstructor
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
    private LocalDateTime createdAt;
}
