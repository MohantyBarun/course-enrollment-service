package com.seatsure.payment.mock;

import com.seatsure.payment.WebhookSigner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/**
 * The mock gateway's side of webhooks: POSTs each queued event to SeatSure's webhook URL, signed,
 * and retries with exponential backoff (2s, 4s, 8s ... capped at 5 minutes) until it gets a 2xx,
 * giving up after {@value #MAX_ATTEMPTS} attempts.
 *
 * <p>Events are claimed with a short lease (an UPDATE that pushes next_attempt_at forward, using
 * SKIP LOCKED) and the HTTP call happens after that commits, so no database transaction or row
 * lock is held while waiting on the network. If this process dies mid-send, the lease runs out
 * and the event is sent again: the receiver de-duplicates by event id.
 */
@Slf4j
@Component
public class MockWebhookDelivery {

    /** Published after an event is queued, so delivery doesn't wait for the next poll. */
    public record EventQueued(String eventId) {
    }

    static final int MAX_ATTEMPTS = 8;
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private final JdbcTemplate jdbc;
    private final WebhookSigner signer;
    private final Environment environment;
    private final String configuredUrl;
    private final Clock clock;
    private final RestClient http;

    public MockWebhookDelivery(JdbcTemplate jdbc, WebhookSigner signer, Environment environment,
                               @Value("${app.payments.webhook-url:}") String configuredUrl, Clock clock) {
        this.jdbc = jdbc;
        this.signer = signer;
        this.environment = environment;
        this.configuredUrl = configuredUrl;
        this.clock = clock;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onQueued(EventQueued queued) {
        deliverDue();
    }

    @Scheduled(fixedDelayString = "${app.payments.mock.delivery-interval:PT2S}", initialDelayString = "PT10S")
    public void poll() {
        deliverDue();
    }

    private record Claimed(String id, String payload, int attempts) {
    }

    public int deliverDue() {
        Instant now = clock.instant();
        // Autocommitted on its own: the lease is visible to other instances before any HTTP call
        List<Claimed> batch = jdbc.query("""
                UPDATE mock_gateway_events SET next_attempt_at = ?
                WHERE id IN (SELECT id FROM mock_gateway_events
                             WHERE status = 'PENDING' AND next_attempt_at <= ?
                             ORDER BY created_at LIMIT 20 FOR UPDATE SKIP LOCKED)
                RETURNING id, payload, attempts
                """, (rs, i) -> new Claimed(rs.getString("id"), rs.getString("payload"), rs.getInt("attempts")),
                now.plus(LEASE).atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC));
        batch.forEach(this::send);
        return batch.size();
    }

    private void send(Claimed event) {
        String url = webhookUrl();
        try {
            http.post().uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(WebhookSigner.HEADER, signer.sign(event.payload()))
                    .body(event.payload())
                    .retrieve()
                    .toBodilessEntity(); // non-2xx throws
            jdbc.update("UPDATE mock_gateway_events SET status = 'DELIVERED', attempts = attempts + 1, delivered_at = ? "
                    + "WHERE id = ?", clock.instant().atOffset(ZoneOffset.UTC), event.id());
            log.info("[mock gateway] delivered {} to {}", event.id(), url);
        } catch (Exception e) {
            int attempts = event.attempts() + 1;
            String error = e.getClass().getSimpleName() + ": " + e.getMessage();
            if (attempts >= MAX_ATTEMPTS) {
                jdbc.update("UPDATE mock_gateway_events SET status = 'FAILED', attempts = ?, last_error = ? WHERE id = ?",
                        attempts, truncate(error), event.id());
                log.error("[mock gateway] gave up on {} after {} attempts: {}", event.id(), attempts, error);
                return;
            }
            Duration backoff = Duration.ofSeconds(2L << Math.min(attempts - 1, 16));
            if (backoff.compareTo(MAX_BACKOFF) > 0) {
                backoff = MAX_BACKOFF;
            }
            jdbc.update("UPDATE mock_gateway_events SET attempts = ?, last_error = ?, next_attempt_at = ? WHERE id = ?",
                    attempts, truncate(error), clock.instant().plus(backoff).atOffset(ZoneOffset.UTC), event.id());
            log.warn("[mock gateway] delivery of {} failed (attempt {}), retrying in {}s: {}", event.id(), attempts,
                    backoff.toSeconds(), error);
        }
    }

    /** Configured URL, or this same server (the mock gateway "calls back" the app it runs in). */
    String webhookUrl() {
        if (!configuredUrl.isBlank()) {
            return configuredUrl;
        }
        String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "8080"));
        return "http://localhost:" + port + "/webhooks/payments";
    }

    private static String truncate(String s) {
        return s.length() <= 500 ? s : s.substring(0, 500);
    }
}
