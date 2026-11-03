package com.seatsure.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A domain event stored in the same transaction as the change that caused it (transactional outbox).
 * OutboxRelay delivers it afterwards, so an event is never lost when the broker is down and never
 * sent for a change that was rolled back.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "outbox_events")
public class OutboxEvent {

    public enum Status { PENDING, PUBLISHED, FAILED }

    private static final Duration MAX_BACKOFF = Duration.ofHours(1);

    @Id
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 50)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    public static OutboxEvent pending(String aggregateType, String aggregateId, String eventType, String payload,
                                      Instant now) {
        OutboxEvent e = new OutboxEvent();
        e.id = UUID.randomUUID();
        e.aggregateType = aggregateType;
        e.aggregateId = aggregateId;
        e.eventType = eventType;
        e.payload = payload;
        e.createdAt = now;
        e.nextAttemptAt = now;
        return e;
    }

    /**
     * Claimed by a relay: other relays skip it until {@code until}. If the claiming relay dies
     * mid-send, the event becomes due again then (at-least-once; consumers de-duplicate by id).
     */
    public void lease(Instant until) {
        nextAttemptAt = until;
    }

    public void markPublished(Instant now) {
        status = Status.PUBLISHED;
        publishedAt = now;
        lastError = null;
    }

    /** Schedules a retry with exponential backoff (5s, 10s, 20s ... capped at 1h), or gives up. */
    public void markFailed(String error, Instant now, int maxAttempts) {
        attempts++;
        lastError = error == null ? null : error.substring(0, Math.min(error.length(), 1000));
        if (attempts >= maxAttempts) {
            status = Status.FAILED;
            return;
        }
        Duration backoff = Duration.ofSeconds(5L << Math.min(attempts - 1, 20));
        nextAttemptAt = now.plus(backoff.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff);
    }
}
