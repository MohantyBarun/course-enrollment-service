package com.seatsure.repository;

import com.seatsure.entity.ShowSeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * Every query that locks seats does it in one statement, in id order. Two transactions that want
 * overlapping seats therefore always queue on the lowest common seat instead of deadlocking.
 */
public interface ShowSeatRepository extends JpaRepository<ShowSeat, Long> {

    /** Locks the requested seats of a show (the caller then checks they're still available). */
    @Query(value = """
            SELECT * FROM show_seats
            WHERE show_id = :showId AND seat_id IN (:seatIds)
            ORDER BY id
            FOR UPDATE
            """, nativeQuery = true)
    List<ShowSeat> lockSeats(@Param("showId") Long showId, @Param("seatIds") Collection<Long> seatIds);

    @Query(value = "SELECT * FROM show_seats WHERE hold_id = :holdId ORDER BY id FOR UPDATE", nativeQuery = true)
    List<ShowSeat> lockByHold(@Param("holdId") Long holdId);

    @Query(value = "SELECT * FROM show_seats WHERE booking_id = :bookingId ORDER BY id FOR UPDATE", nativeQuery = true)
    List<ShowSeat> lockByBooking(@Param("bookingId") Long bookingId);

    @Query("SELECT ss FROM ShowSeat ss JOIN FETCH ss.seat WHERE ss.hold.id = :holdId")
    List<ShowSeat> findByHold(@Param("holdId") Long holdId);

    /** Seat ids only (no entities cached ahead of the FOR UPDATE that follows). */
    @Query("SELECT ss.seat.id FROM ShowSeat ss WHERE ss.hold.id = :holdId")
    List<Long> seatIdsOfHold(@Param("holdId") Long holdId);

    /** The seat map: every seat of the show with its current state. */
    @Query("SELECT ss FROM ShowSeat ss JOIN FETCH ss.seat WHERE ss.show.id = :showId")
    List<ShowSeat> findSeatMap(@Param("showId") Long showId);

    @Query("""
            SELECT COUNT(ss) FROM ShowSeat ss
            WHERE ss.show.id = :showId AND ss.status = com.seatsure.entity.ShowSeatStatus.AVAILABLE
            """)
    long countAvailable(@Param("showId") Long showId);

    /** [showId, total seats, available seats] per show, for showtime availability badges. */
    @Query("""
            SELECT ss.show.id, COUNT(ss), SUM(CASE WHEN ss.status = com.seatsure.entity.ShowSeatStatus.AVAILABLE THEN 1 ELSE 0 END)
            FROM ShowSeat ss WHERE ss.show.id IN :showIds GROUP BY ss.show.id
            """)
    List<Object[]> availability(@Param("showIds") Collection<Long> showIds);

    /** Creates the inventory for a new show: one AVAILABLE row per seat of its screen. */
    @Modifying
    @Query(value = """
            INSERT INTO show_seats (show_id, seat_id, status)
            SELECT :showId, s.id, 'AVAILABLE' FROM seats s WHERE s.screen_id = :screenId
            """, nativeQuery = true)
    int createInventory(@Param("showId") Long showId, @Param("screenId") Long screenId);
}
