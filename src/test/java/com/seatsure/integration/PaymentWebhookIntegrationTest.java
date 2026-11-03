package com.seatsure.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsure.dto.CheckoutDtos.HoldView;
import com.seatsure.dto.CheckoutDtos.PaymentStarted;
import com.seatsure.outbox.OutboxEvents;
import com.seatsure.payment.PaymentService;
import com.seatsure.payment.WebhookEvent;
import com.seatsure.payment.WebhookSigner;
import com.seatsure.service.HoldService;
import com.seatsure.support.FullAppIntegrationTest;
import com.seatsure.support.TestFixtures;
import com.seatsure.support.TestFixtures.ShowFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The webhook receiver: signatures, exactly-once processing, and payments that arrive too late. */
@FullAppIntegrationTest
class PaymentWebhookIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private WebhookSigner signer;
    @Autowired private HoldService holds;
    @Autowired private PaymentService payments;
    @Autowired private TestFixtures fixtures;
    @Autowired private JdbcTemplate jdbc;

    private record Started(Long userId, HoldView hold, PaymentStarted payment) {
    }

    private Started startPayment(ShowFixture show, int seats) {
        Long user = fixtures.customer().user().id();
        HoldView hold = holds.create(user, show.showId(), show.seatIds().subList(0, seats));
        return new Started(user, hold, payments.start(user, hold.holdId()));
    }

    private String body(String type, PaymentStarted payment) throws Exception {
        return objectMapper.writeValueAsString(new WebhookEvent("evt_" + UUID.randomUUID(), type, payment.gatewayRef(),
                payment.amountPaise(), null, Instant.now().getEpochSecond()));
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    @DisplayName("an unsigned or forged webhook is refused with 401 and changes nothing")
    void badSignature_isRejected() throws Exception {
        Started started = startPayment(fixtures.show(1, 2), 1);
        String body = body(WebhookEvent.PAYMENT_SUCCEEDED, started.payment());

        mockMvc.perform(post("/webhooks/payments").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/webhooks/payments").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header(WebhookSigner.HEADER, signer.sign(body.replace("payment.succeeded", "payment.failed"))))
                .andExpect(status().isUnauthorized());

        assertThat(count("SELECT COUNT(*) FROM payments WHERE gateway_ref = ? AND status = 'PENDING'",
                started.payment().gatewayRef())).isEqualTo(1);
    }

    @Test
    @DisplayName("the same event delivered twice is processed once (one booking)")
    void redeliveredEvent_isProcessedOnce() throws Exception {
        Started started = startPayment(fixtures.show(1, 4), 2);
        String body = body(WebhookEvent.PAYMENT_SUCCEEDED, started.payment());

        for (String expected : new String[]{"PROCESSED", "DUPLICATE"}) {
            mockMvc.perform(post("/webhooks/payments").contentType(MediaType.APPLICATION_JSON).content(body)
                            .header(WebhookSigner.HEADER, signer.sign(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result").value(expected));
        }

        assertThat(count("SELECT COUNT(*) FROM bookings WHERE payment_id = (SELECT id FROM payments WHERE gateway_ref = ?)",
                started.payment().gatewayRef())).isEqualTo(1);
    }

    @Test
    @DisplayName("a second, different success event for an already-paid payment changes nothing")
    void secondSuccessEvent_isIgnored() throws Exception {
        Started started = startPayment(fixtures.show(1, 4), 1);
        for (int i = 0; i < 2; i++) {
            String body = body(WebhookEvent.PAYMENT_SUCCEEDED, started.payment()); // new event id each time
            mockMvc.perform(post("/webhooks/payments").contentType(MediaType.APPLICATION_JSON).content(body)
                            .header(WebhookSigner.HEADER, signer.sign(body)))
                    .andExpect(status().isOk());
        }
        assertThat(count("SELECT COUNT(*) FROM bookings WHERE payment_id = (SELECT id FROM payments WHERE gateway_ref = ?)",
                started.payment().gatewayRef())).isEqualTo(1);
    }

    @Test
    @DisplayName("money that arrives after the hold lapsed is refunded, not turned into a booking")
    void latePayment_isRefunded() throws Exception {
        ShowFixture show = fixtures.show(1, 4);
        Started started = startPayment(show, 2);
        fixtures.ageOut(started.hold().holdId());
        holds.expireDue();

        String body = body(WebhookEvent.PAYMENT_SUCCEEDED, started.payment());
        mockMvc.perform(post("/webhooks/payments").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header(WebhookSigner.HEADER, signer.sign(body)))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE gateway_ref = ?", String.class,
                started.payment().gatewayRef())).isEqualTo("REFUNDED");
        assertThat(count("SELECT COUNT(*) FROM bookings WHERE show_id = ?", show.showId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE event_type = ? AND payload LIKE ?",
                OutboxEvents.PAYMENT_REFUNDED, "%" + started.payment().gatewayRef() + "%")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM show_seats WHERE show_id = ? AND status = 'AVAILABLE'", show.showId()))
                .isEqualTo(4);
    }

    @Test
    @DisplayName("a payment for the wrong amount is never confirmed: it's refunded and the seats stay on hold")
    void wrongAmount_isRefundedNotBooked() {
        Started started = startPayment(fixtures.show(1, 2), 1);
        String ref = started.payment().gatewayRef();

        payments.onSucceeded(ref, started.payment().amountPaise() + 100);

        assertThat(count("SELECT COUNT(*) FROM payments WHERE gateway_ref = ? AND status = 'REFUNDED'", ref)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM seat_holds WHERE id = ? AND status = 'ACTIVE'", started.hold().holdId()))
                .as("no booking: the customer can pay again while the hold lasts").isEqualTo(1);
    }
}
