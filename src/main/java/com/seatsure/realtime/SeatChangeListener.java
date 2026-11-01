package com.seatsure.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;

/**
 * LISTENs for seat changes on a dedicated database connection (not one borrowed from the pool,
 * which is small on the free tier) and hands them to {@link SeatStreams}. Reconnects with backoff
 * if the connection drops; clients reload their map when their own stream reconnects, so nothing
 * is lost for good during a gap.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.realtime.enabled", havingValue = "true", matchIfMissing = true)
public class SeatChangeListener {

    private static final int POLL_MILLIS = 1000;
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private final JdbcConnectionDetails db;
    private final SeatStreams streams;
    private final ObjectMapper objectMapper;
    private volatile boolean running;
    private volatile boolean listening;
    private Thread thread;

    public SeatChangeListener(JdbcConnectionDetails db, SeatStreams streams, ObjectMapper objectMapper) {
        this.db = db;
        this.streams = streams;
        this.objectMapper = objectMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        running = true;
        thread = Thread.ofPlatform().name("seat-change-listener").daemon().start(this::loop);
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    /** True once LISTEN is active (used by tests to avoid racing the first notification). */
    public boolean isListening() {
        return listening;
    }

    private void loop() {
        Duration backoff = Duration.ofSeconds(1);
        while (running) {
            try (Connection connection = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword())) {
                try (Statement st = connection.createStatement()) {
                    st.execute("LISTEN " + SeatEvents.CHANNEL);
                }
                PGConnection pg = connection.unwrap(PGConnection.class);
                listening = true;
                backoff = Duration.ofSeconds(1);
                log.info("Listening for seat changes");
                while (running) {
                    PGNotification[] notifications = pg.getNotifications(POLL_MILLIS);
                    if (notifications == null) {
                        continue;
                    }
                    for (PGNotification n : notifications) {
                        dispatch(n.getParameter());
                    }
                }
            } catch (Exception e) {
                listening = false;
                if (!running) {
                    return;
                }
                log.warn("Seat change listener lost its connection; retrying in {}s: {}", backoff.toSeconds(), e.getMessage());
                try {
                    Thread.sleep(backoff.toMillis());
                } catch (InterruptedException ie) {
                    return;
                }
                backoff = backoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff.multipliedBy(2);
            }
        }
    }

    void dispatch(String json) {
        try {
            streams.publish(objectMapper.readValue(json, SeatEvents.Payload.class));
        } catch (Exception e) {
            log.warn("Could not dispatch seat change {}: {}", json, e.getMessage());
        }
    }
}
