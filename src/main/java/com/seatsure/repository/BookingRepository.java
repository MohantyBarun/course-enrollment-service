package com.seatsure.repository;

import com.seatsure.entity.Booking;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    boolean existsByReference(String reference);

    @Query("""
            SELECT b FROM Booking b JOIN FETCH b.show s JOIN FETCH s.movie JOIN FETCH s.screen sc
            JOIN FETCH sc.cinema c JOIN FETCH c.city JOIN FETCH b.user
            WHERE b.reference = :reference
            """)
    Optional<Booking> findDetailed(@Param("reference") String reference);

    /** Locked: a cancel racing another cancel (or a show cancellation) must see the other's result. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Booking b WHERE b.reference = :reference")
    Optional<Booking> findByReferenceForUpdate(@Param("reference") String reference);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Booking b WHERE b.show.id = :showId AND b.status = com.seatsure.entity.BookingStatus.CONFIRMED ORDER BY b.id")
    List<Booking> findConfirmedForShowForUpdate(@Param("showId") Long showId);

    @Query(value = """
            SELECT b FROM Booking b JOIN FETCH b.show s JOIN FETCH s.movie JOIN FETCH s.screen sc JOIN FETCH sc.cinema
            WHERE b.user.id = :userId ORDER BY s.startsAt DESC
            """,
            countQuery = "SELECT COUNT(b) FROM Booking b WHERE b.user.id = :userId")
    Page<Booking> findForUser(@Param("userId") Long userId, Pageable pageable);

    @Query(value = """
            SELECT b FROM Booking b JOIN FETCH b.show s JOIN FETCH s.movie JOIN FETCH s.screen sc
            JOIN FETCH sc.cinema JOIN FETCH b.user ORDER BY b.createdAt DESC
            """,
            countQuery = "SELECT COUNT(b) FROM Booking b")
    Page<Booking> findRecent(Pageable pageable);

    // ---------------------------------------------------------------- admin dashboard aggregates

    @Query("""
            SELECT COUNT(b), COALESCE(SUM(b.seatCount), 0), COALESCE(SUM(b.ticketsPaise + b.convenienceFeePaise), 0)
            FROM Booking b WHERE b.status = com.seatsure.entity.BookingStatus.CONFIRMED AND b.createdAt >= :since
            """)
    List<Object[]> totalsSince(@Param("since") Instant since);

    /** [movie title, tickets, revenue paise] for confirmed bookings since a time, best sellers first. */
    @Query("""
            SELECT m.title, SUM(b.seatCount), SUM(b.ticketsPaise + b.convenienceFeePaise)
            FROM Booking b JOIN b.show s JOIN s.movie m
            WHERE b.status = com.seatsure.entity.BookingStatus.CONFIRMED AND b.createdAt >= :since
            GROUP BY m.id, m.title ORDER BY SUM(b.seatCount) DESC
            """)
    List<Object[]> topMoviesSince(@Param("since") Instant since, Pageable pageable);

    /** [createdAt, seatCount, total paise] of confirmed bookings since a time, bucketed per day by the caller. */
    @Query("""
            SELECT b.createdAt, b.seatCount, b.ticketsPaise + b.convenienceFeePaise FROM Booking b
            WHERE b.status = com.seatsure.entity.BookingStatus.CONFIRMED AND b.createdAt >= :since
            """)
    List<Object[]> confirmedSince(@Param("since") Instant since);
}
