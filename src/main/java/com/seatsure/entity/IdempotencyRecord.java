package com.seatsure.entity;

import lombok.AccessLevel;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** The outcome of a request sent with an Idempotency-Key, replayed if the same request is retried. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "idempotency_keys",
        uniqueConstraints = @UniqueConstraint(name = "uk_idempotency_owner_key", columnNames = {"owner", "idempotency_key"}))
public class IdempotencyRecord {

    public enum Status { IN_PROGRESS, COMPLETED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Keys are scoped per user, so two users can't collide on (or read) each other's keys. */
    @Column(name = "owner", nullable = false, length = 150)
    private String owner;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    /** SHA-256 of method, path and body: the same key with a different request is rejected. */
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "response_status")
    private Integer responseStatus;

    /** Encrypted (SecretBox): stored responses can contain personal data such as names and emails. */
    @Column(name = "response_body", columnDefinition = "text")
    private String responseBody;

    @Column(name = "response_content_type", length = 100)
    private String responseContentType;

    @Column(name = "response_location", length = 500)
    private String responseLocation;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** A key just claimed by a request that is now running. */
    public static IdempotencyRecord inProgress(String owner, String key, String requestHash, Instant now, Instant expiresAt) {
        IdempotencyRecord r = new IdempotencyRecord();
        r.owner = owner;
        r.idempotencyKey = key;
        r.requestHash = requestHash;
        r.status = Status.IN_PROGRESS;
        r.createdAt = now;
        r.expiresAt = expiresAt;
        return r;
    }

    /** The request finished: its response (body already encrypted) is replayed for retries. */
    public void complete(int responseStatus, String encryptedBody, String contentType, String location) {
        this.status = Status.COMPLETED;
        this.responseStatus = responseStatus;
        this.responseBody = encryptedBody;
        this.responseContentType = contentType;
        this.responseLocation = location;
    }
}
