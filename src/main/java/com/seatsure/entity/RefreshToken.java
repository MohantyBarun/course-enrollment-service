package com.seatsure.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * A refresh token, stored as the SHA-256 of the opaque value handed to the client. Tokens rotate on
 * every use within a family (one family per login); reuse of a rotated token revokes the family.
 */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public RefreshToken(User user, String tokenHash, UUID familyId, Instant now, Instant expiresAt) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.familyId = familyId;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public void revoke(Instant now) {
        this.revokedAt = now;
    }
}
