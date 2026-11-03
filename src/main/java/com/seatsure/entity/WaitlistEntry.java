package com.seatsure.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** A customer queued for a sold-out show. */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "waitlist_entries")
public class WaitlistEntry implements OwnedByUser {

    /**
     * WAITING in the queue; OFFERED seats as a hold; FULFILLED (paid); LAPSED (the offer expired);
     * LEFT (the customer left the queue); CLOSED (the show started or was cancelled).
     */
    public enum Status { WAITING, OFFERED, FULFILLED, LAPSED, LEFT, CLOSED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "show_id", nullable = false)
    private Show show;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "seats_wanted", nullable = false)
    private int seatsWanted;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.WAITING;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hold_id")
    private SeatHold hold;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public WaitlistEntry(Show show, User user, int seatsWanted, Instant now) {
        this.show = show;
        this.user = user;
        this.seatsWanted = seatsWanted;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void moveTo(Status next, Instant now) {
        this.status = next;
        this.updatedAt = now;
    }

    /** Seats were freed and are now held for this entry. */
    public void offer(SeatHold hold, Instant now) {
        this.hold = hold;
        moveTo(Status.OFFERED, now);
    }
}
