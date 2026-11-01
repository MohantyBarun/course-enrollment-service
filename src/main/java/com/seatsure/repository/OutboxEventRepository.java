package com.seatsure.repository;

import com.seatsure.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Claims the next due events. FOR UPDATE SKIP LOCKED lets several relays (instances or threads)
     * run at once: each locks a different batch and none waits on rows another one is sending.
     */
    @Query(value = "SELECT * FROM outbox_events WHERE status = 'PENDING' AND next_attempt_at <= :now "
            + "ORDER BY created_at LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEvent> lockNextBatch(@Param("now") LocalDateTime now, @Param("limit") int limit);

    long countByStatus(OutboxEvent.Status status);

    java.util.Optional<OutboxEvent> findFirstByEventTypeOrderByCreatedAtDesc(String eventType);

    /** Newest first, for the admin Emails page. */
    List<OutboxEvent> findTop50ByOrderByCreatedAtDesc();

    @Modifying
    @Query("DELETE FROM OutboxEvent e WHERE e.status = :status AND e.publishedAt < :before")
    int deleteByStatusAndPublishedAtBefore(@Param("status") OutboxEvent.Status status,
                                           @Param("before") LocalDateTime before);
}
