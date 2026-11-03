package com.seatsure.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Duration;
import java.time.Instant;

/** One screening of a movie on a screen. The database forbids two scheduled shows overlapping on a screen. */
@Entity
@Getter
@Setter
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

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

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
}
