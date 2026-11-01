package com.seatsure.repository;

import com.seatsure.entity.ShowSeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ShowSeatRepository extends JpaRepository<ShowSeat, Long> {

    /**
     * Locks the requested seats of a show. Rows are locked in id order: two bookings that overlap
     * (A wants 1,2; B wants 2,1) both lock 1 first, so one waits for the other instead of deadlocking.
     * The caller then checks they're all still available, inside the same transaction.
     */
    @Query(value = """
            SELECT * FROM show_seats
            WHERE show_id = :showId AND seat_id IN (:seatIds)
            ORDER BY id
            FOR UPDATE
            """, nativeQuery = true)
    List<ShowSeat> lockSeats(@Param("showId") Long showId, @Param("seatIds") Collection<Long> seatIds);

    /** The seat map: every seat of the show with its current state. */
    @Query("SELECT ss FROM ShowSeat ss JOIN FETCH ss.seat WHERE ss.show.id = :showId")
    List<ShowSeat> findSeatMap(@Param("showId") Long showId);

    /** [showId, total seats, available seats] per show, for showtime availability badges. */
    @Query("""
            SELECT ss.show.id, COUNT(ss), SUM(CASE WHEN ss.status = com.seatsure.entity.ShowSeatStatus.AVAILABLE THEN 1 ELSE 0 END)
            FROM ShowSeat ss WHERE ss.show.id IN :showIds GROUP BY ss.show.id
            """)
    List<Object[]> availability(@Param("showIds") Collection<Long> showIds);

    @Modifying
    @Query("""
            UPDATE ShowSeat ss SET ss.status = com.seatsure.entity.ShowSeatStatus.AVAILABLE, ss.booking = null
            WHERE ss.booking.id = :bookingId
            """)
    int releaseBooking(@Param("bookingId") Long bookingId);

    /** Creates the inventory for a new show: one AVAILABLE row per seat of its screen. */
    @Modifying
    @Query(value = """
            INSERT INTO show_seats (show_id, seat_id, status)
            SELECT :showId, s.id, 'AVAILABLE' FROM seats s WHERE s.screen_id = :screenId
            """, nativeQuery = true)
    int createInventory(@Param("showId") Long showId, @Param("screenId") Long screenId);
}
