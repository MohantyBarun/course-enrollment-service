package com.seatsure.repository;

import com.seatsure.entity.WaitlistEntry;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WaitlistRepository extends JpaRepository<WaitlistEntry, Long> {

    /** The queue for a show, oldest first, locked so two releases can't offer the same entry. */
    @Query(value = """
            SELECT * FROM waitlist_entries WHERE show_id = :showId AND status = 'WAITING'
            ORDER BY created_at, id FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<WaitlistEntry> lockQueue(@Param("showId") Long showId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM WaitlistEntry w WHERE w.id = :id")
    Optional<WaitlistEntry> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM WaitlistEntry w WHERE w.hold.id = :holdId")
    Optional<WaitlistEntry> findByHoldForUpdate(@Param("holdId") Long holdId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT w FROM WaitlistEntry w WHERE w.show.id = :showId
              AND w.status IN (com.seatsure.entity.WaitlistEntry.Status.WAITING, com.seatsure.entity.WaitlistEntry.Status.OFFERED)
            ORDER BY w.id
            """)
    List<WaitlistEntry> findOpenForShowForUpdate(@Param("showId") Long showId);

    @Query("""
            SELECT COUNT(w) > 0 FROM WaitlistEntry w WHERE w.show.id = :showId AND w.user.id = :userId
              AND w.status IN (com.seatsure.entity.WaitlistEntry.Status.WAITING, com.seatsure.entity.WaitlistEntry.Status.OFFERED)
            """)
    boolean isOpenForUser(@Param("showId") Long showId, @Param("userId") Long userId);

    /** How many WAITING entries joined before this one (its place in the queue is this + 1). */
    @Query("""
            SELECT COUNT(w) FROM WaitlistEntry w WHERE w.show.id = :showId
              AND w.status = com.seatsure.entity.WaitlistEntry.Status.WAITING AND w.createdAt < :createdAt
            """)
    long countAhead(@Param("showId") Long showId, @Param("createdAt") java.time.Instant createdAt);

    @Query("""
            SELECT w FROM WaitlistEntry w JOIN FETCH w.show s JOIN FETCH s.movie JOIN FETCH s.screen sc JOIN FETCH sc.cinema
            WHERE w.user.id = :userId ORDER BY w.createdAt DESC
            """)
    List<WaitlistEntry> findForUser(@Param("userId") Long userId);
}
