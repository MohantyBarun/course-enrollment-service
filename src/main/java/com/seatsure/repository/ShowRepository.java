package com.seatsure.repository;

import com.seatsure.entity.Show;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ShowRepository extends JpaRepository<Show, Long> {

    @Query("""
            SELECT s FROM Show s JOIN FETCH s.movie JOIN FETCH s.screen sc JOIN FETCH sc.cinema c JOIN FETCH c.city
            WHERE s.id = :id
            """)
    Optional<Show> findDetailed(@Param("id") Long id);

    /**
     * Shared lock on the show row, held for the whole booking. Bookings don't block each other (shared
     * locks are compatible), but a show cancellation ({@link #findByIdForUpdate}) waits for bookings in
     * flight and then blocks new ones, so no booking can slip in after the cancellation's sweep.
     */
    @Query(value = "SELECT id FROM shows WHERE id = :id FOR SHARE", nativeQuery = true)
    Optional<Long> lockForShare(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Show s WHERE s.id = :id")
    Optional<Show> findByIdForUpdate(@Param("id") Long id);

    /** Scheduled shows of a movie in a city within [from, to), for the "pick a showtime" page. */
    @Query("""
            SELECT s FROM Show s JOIN FETCH s.screen sc JOIN FETCH sc.cinema c
            WHERE s.movie.id = :movieId AND c.city.id = :cityId
              AND s.status = com.seatsure.entity.ShowStatus.SCHEDULED
              AND s.startsAt >= :from AND s.startsAt < :to
            ORDER BY c.name, s.startsAt
            """)
    List<Show> findForMovieInCity(@Param("movieId") Long movieId, @Param("cityId") Long cityId,
                                  @Param("from") Instant from, @Param("to") Instant to);

    /** Shows at a cinema within [from, to), any status, for the admin schedule. */
    @Query("""
            SELECT s FROM Show s JOIN FETCH s.movie JOIN FETCH s.screen sc
            WHERE sc.cinema.id = :cinemaId AND s.startsAt >= :from AND s.startsAt < :to
            ORDER BY sc.name, s.startsAt
            """)
    List<Show> findForCinema(@Param("cinemaId") Long cinemaId, @Param("from") Instant from, @Param("to") Instant to);

    @Query("""
            SELECT s FROM Show s JOIN FETCH s.movie JOIN FETCH s.screen sc JOIN FETCH sc.cinema
            WHERE s.status = com.seatsure.entity.ShowStatus.SCHEDULED AND s.startsAt >= :from AND s.startsAt < :to
            """)
    List<Show> findScheduledBetween(@Param("from") Instant from, @Param("to") Instant to);

    /** Which upcoming days have shows of a movie in a city (for the date strip). */
    @Query("""
            SELECT s.startsAt FROM Show s JOIN s.screen sc JOIN sc.cinema c
            WHERE s.movie.id = :movieId AND c.city.id = :cityId
              AND s.status = com.seatsure.entity.ShowStatus.SCHEDULED AND s.startsAt > :now
            """)
    List<Instant> findUpcomingStartTimes(@Param("movieId") Long movieId, @Param("cityId") Long cityId,
                                         @Param("now") Instant now);
}
