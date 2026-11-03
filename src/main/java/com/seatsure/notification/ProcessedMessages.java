package com.seatsure.notification;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.UUID;

/** Message ids already handled, so redelivered messages (at-least-once delivery) are ignored. */
@Component
@RequiredArgsConstructor
public class ProcessedMessages {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    /**
     * Records the id in the caller's transaction; false if it was already recorded. A concurrent
     * duplicate blocks on the unique key until the first transaction finishes, then gets false.
     */
    public boolean markProcessed(UUID messageId) {
        int inserted = jdbcTemplate.update(
                "INSERT INTO processed_messages (message_id, processed_at) VALUES (?, ?) ON CONFLICT (message_id) DO NOTHING",
                messageId, clock.instant().atOffset(ZoneOffset.UTC));
        return inserted == 1;
    }

    @Transactional
    @Scheduled(cron = "0 20 3 * * *", zone = "Asia/Kolkata")
    @SchedulerLock(name = "purge-processed-messages", lockAtLeastFor = "PT1M")
    public void purgeOld() {
        jdbcTemplate.update("DELETE FROM processed_messages WHERE processed_at < ?",
                clock.instant().minus(Duration.ofDays(30)).atOffset(ZoneOffset.UTC));
    }
}
