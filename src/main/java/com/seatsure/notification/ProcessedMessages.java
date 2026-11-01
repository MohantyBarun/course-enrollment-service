package com.seatsure.notification;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

/** Message ids already handled, so redelivered messages (at-least-once delivery) are ignored. */
@Component
@RequiredArgsConstructor
public class ProcessedMessages {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Records the id in the caller's transaction; false if it was already recorded. A concurrent
     * duplicate blocks on the unique key until the first transaction finishes, then gets false.
     */
    public boolean markProcessed(UUID messageId) {
        int inserted = jdbcTemplate.update(
                "INSERT INTO processed_messages (message_id, processed_at) VALUES (?, ?) ON CONFLICT (message_id) DO NOTHING",
                messageId, Timestamp.valueOf(LocalDateTime.now()));
        return inserted == 1;
    }

    @Transactional
    @Scheduled(cron = "0 20 3 * * *", zone = "Asia/Kolkata")
    public void purgeOld() {
        jdbcTemplate.update("DELETE FROM processed_messages WHERE processed_at < ?",
                Timestamp.valueOf(LocalDateTime.now().minusDays(30)));
    }
}
