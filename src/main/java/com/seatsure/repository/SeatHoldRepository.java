package com.seatsure.repository;

import com.seatsure.entity.SeatHold;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SeatHoldRepository extends JpaRepository<SeatHold, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT h FROM SeatHold h WHERE h.id = :id")
    Optional<SeatHold> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT h FROM SeatHold h
            WHERE h.user.id = :userId AND h.show.id = :showId AND h.status = com.seatsure.entity.SeatHold.Status.ACTIVE
            """)
    Optional<SeatHold> findActiveForUpdate(@Param("userId") Long userId, @Param("showId") Long showId);

    @Query("""
            SELECT COUNT(h) > 0 FROM SeatHold h
            WHERE h.user.id = :userId AND h.show.id = :showId AND h.status = com.seatsure.entity.SeatHold.Status.ACTIVE
            """)
    boolean existsActive(@Param("userId") Long userId, @Param("showId") Long showId);

    /**
     * Claims expired holds for the expiry job. SKIP LOCKED: a hold being paid for right now (its
     * row is locked by the payment confirmation) is skipped, and several instances can run the job.
     */
    @Query(value = """
            SELECT * FROM seat_holds WHERE status = 'ACTIVE' AND expires_at < :now
            ORDER BY expires_at LIMIT :limit FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<SeatHold> lockExpired(@Param("now") Instant now, @Param("limit") int limit);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT h FROM SeatHold h WHERE h.show.id = :showId AND h.status = com.seatsure.entity.SeatHold.Status.ACTIVE
            ORDER BY h.id
            """)
    List<SeatHold> findActiveForShowForUpdate(@Param("showId") Long showId);

    @Query("""
            SELECT h FROM SeatHold h JOIN FETCH h.show s JOIN FETCH s.movie JOIN FETCH s.screen sc
            JOIN FETCH sc.cinema c JOIN FETCH c.city LEFT JOIN FETCH h.booking
            WHERE h.id = :id
            """)
    Optional<SeatHold> findDetailed(@Param("id") Long id);
}
