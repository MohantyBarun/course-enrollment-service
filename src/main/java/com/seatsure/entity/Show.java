package com.seatsure.entity;

import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.springframework.data.annotation.CreatedDate;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;

/** One screening of a movie on a screen. The database forbids two scheduled shows overlapping on a screen. */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor
@Table(name = "shows")
public class Show {

    /** Time between shows for cleaning and seating, included in {@code endsAt}. */
    public static final Duration TURNAROUND = Duration.ofMinutes(15);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "movie_id", nullable = false)
    private Movie movie;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "screen_id", nullable = false)
    private Screen screen;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Convert(converter = ShowFormat.DbConverter.class)
    @Column(name = "format", nullable = false, length = 10)
    private ShowFormat format;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ShowStatus status = ShowStatus.SCHEDULED;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Show(Movie movie, Screen screen, Instant startsAt, ShowFormat format) {
        this.movie = movie;
        this.screen = screen;
        this.startsAt = startsAt;
        this.endsAt = startsAt.plus(Duration.ofMinutes(movie.getDurationMinutes())).plus(TURNAROUND);
        this.format = format;
    }

    /** Bookings close when the show starts. */
    public boolean isBookable(Instant now) {
        return status == ShowStatus.SCHEDULED && now.isBefore(startsAt);
    }

    public void cancel() {
        this.status = ShowStatus.CANCELLED;
    }
}
