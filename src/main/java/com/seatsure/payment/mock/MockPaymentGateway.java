package com.seatsure.payment.mock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsure.payment.PaymentGateway;
import com.seatsure.payment.WebhookEvent;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

/**
 * A stand-in payment provider ("SeatSure Test Pay"): no money moves. It behaves like a real one in
 * the ways that matter to the booking code: the result of a payment arrives later, as a signed
 * webhook over HTTP, possibly more than once, and is retried until SeatSure acknowledges it.
 */
@Slf4j
@Component
public class MockPaymentGateway implements PaymentGateway {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final MeterRegistry meters;

    public MockPaymentGateway(JdbcTemplate jdbc, ObjectMapper objectMapper, ApplicationEventPublisher events,
                              Clock clock, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.events = events;
        this.clock = clock;
        this.meters = meters;
    }

    @Override
    public String createPayment(String gatewayRef, int amountPaise, String description) {
        log.info("[mock gateway] payment {} created for {} paise: {}", gatewayRef, amountPaise, description);
        return "/pay/" + gatewayRef;
    }

    @Override
    public void refund(String gatewayRef, int amountPaise, String reason) {
        meters.counter("payments.refunds").increment();
        log.info("[mock gateway] refunded {} paise for {}: {}", amountPaise, gatewayRef, reason);
    }

    /**
     * The customer finished on the payment page. Queues the webhook that reports the result;
     * delivery happens after this transaction commits ({@link MockWebhookDelivery}).
     */
    @Transactional
    public void complete(String gatewayRef, int amountPaise, boolean success) {
        Instant now = clock.instant();
        WebhookEvent event = new WebhookEvent("evt_" + random(12),
                success ? WebhookEvent.PAYMENT_SUCCEEDED : WebhookEvent.PAYMENT_FAILED, gatewayRef, amountPaise,
                success ? null : "Declined by the bank (test payment)", now.getEpochSecond());
        jdbc.update("""
                INSERT INTO mock_gateway_events (id, gateway_ref, event_type, payload, status, attempts, next_attempt_at, created_at)
                VALUES (?, ?, ?, ?, 'PENDING', 0, ?, ?)
                """, event.id(), gatewayRef, event.type(), json(event), now.atOffset(ZoneOffset.UTC),
                now.atOffset(ZoneOffset.UTC));
        events.publishEvent(new MockWebhookDelivery.EventQueued(event.id()));
        log.info("[mock gateway] {} for {} queued as {}", event.type(), gatewayRef, event.id());
    }

    public static String newPaymentRef() {
        return "pay_" + random(12);
    }

    private static String random(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private String json(WebhookEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
