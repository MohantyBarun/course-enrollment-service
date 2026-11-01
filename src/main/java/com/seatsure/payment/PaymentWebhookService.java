package com.seatsure.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsure.exception.AuthenticationFailedException;
import com.seatsure.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZoneOffset;

/**
 * Receives payment webhooks. Verifies the signature, then processes each event exactly once:
 * the event id is recorded in the same transaction as the processing, so a redelivery is skipped,
 * and a failure (which rolls both back) makes the gateway retry.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentWebhookService {

    public enum Result { PROCESSED, DUPLICATE, IGNORED }

    private final WebhookSigner signer;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final PaymentService payments;
    private final Clock clock;

    @Transactional
    public Result handle(String rawBody, String signature) {
        if (!signer.verify(signature, rawBody)) {
            throw new AuthenticationFailedException("Invalid webhook signature");
        }
        WebhookEvent event;
        try {
            event = objectMapper.readValue(rawBody, WebhookEvent.class);
        } catch (Exception e) {
            throw new BadRequestException("Malformed webhook payload");
        }
        if (event.id() == null || event.type() == null || event.gatewayRef() == null) {
            throw new BadRequestException("Malformed webhook payload");
        }
        int inserted = jdbc.update("""
                INSERT INTO payment_webhook_events (event_id, event_type, gateway_ref, received_at)
                VALUES (?, ?, ?, ?) ON CONFLICT (event_id) DO NOTHING
                """, event.id(), event.type(), event.gatewayRef(), clock.instant().atOffset(ZoneOffset.UTC));
        if (inserted == 0) {
            log.info("Webhook {} already processed; acknowledging the redelivery", event.id());
            return Result.DUPLICATE;
        }
        switch (event.type()) {
            case WebhookEvent.PAYMENT_SUCCEEDED -> payments.onSucceeded(event.gatewayRef(), event.amountPaise());
            case WebhookEvent.PAYMENT_FAILED -> payments.onFailed(event.gatewayRef(), event.failureReason());
            default -> {
                log.info("Ignoring webhook {} of type {}", event.id(), event.type());
                return Result.IGNORED;
            }
        }
        return Result.PROCESSED;
    }
}
