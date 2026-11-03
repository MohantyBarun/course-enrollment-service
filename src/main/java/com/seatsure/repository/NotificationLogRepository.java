package com.seatsure.repository;

import com.seatsure.entity.NotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {

    List<NotificationLog> findByMessageIdIn(Collection<UUID> messageIds);

    @Modifying
    @Query("DELETE FROM NotificationLog n WHERE n.createdAt < :before")
    int deleteCreatedBefore(@Param("before") LocalDateTime before);
}
