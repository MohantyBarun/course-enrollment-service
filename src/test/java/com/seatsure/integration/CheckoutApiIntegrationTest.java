package com.seatsure.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsure.entity.User;
import com.seatsure.repository.UserRepository;
import com.seatsure.support.FullAppIntegrationTest;
import com.seatsure.support.TestFixtures;
import com.seatsure.support.TestFixtures.ShowFixture;
import com.seatsure.util.JwtUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Checkout over HTTP: hold -> pay on the mock payment page -> the gateway's signed webhook comes
 * back over a real port -> the hold becomes a booking. Plus the access rules and idempotent retries.
 */
@FullAppIntegrationTest
class CheckoutApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TestFixtures fixtures;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private JwtUtil jwtUtil;

    @Test
    @DisplayName("browsing needs no account; the seat map lists every seat")
    void browsing_isPublic() throws Exception {
        ShowFixture show = fixtures.show(2, 8);
        mockMvc.perform(get("/cities")).andExpect(status().isOk());
        mockMvc.perform(get("/shows/{id}", show.showId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookable").value(true))
                .andExpect(jsonPath("$.prices[0].pricePaise").value(TestFixtures.CLASSIC_PRICE));
        mockMvc.perform(get("/shows/{id}/seats", show.showId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(16))
                .andExpect(jsonPath("$.available").value(16))
                .andExpect(jsonPath("$.rows[0].label").value("A"));
    }

    @Test
    @DisplayName("checkout needs a customer token: 401 without one, 403 for admins")
    void checkout_requiresCustomer() throws Exception {
        ShowFixture show = fixtures.show(1, 4);
        String body = holdBody(show.seatIds().subList(0, 1));
        mockMvc.perform(post("/shows/{id}/holds", show.showId()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/shows/{id}/holds", show.showId()).header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("hold -> pay -> webhook over HTTP -> booking; a competing hold gets a 409 naming the seat")
    void checkout_endToEnd() throws Exception {
        ShowFixture show = fixtures.show(1, 6);
        List<Long> seats = show.seatIds();
        String alice = bearer(fixtures.customer().accessToken());
        String bob = bearer(fixtures.customer().accessToken());

        JsonNode hold = json(mockMvc.perform(post("/shows/{id}/holds", show.showId()).header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON).content(holdBody(seats.subList(0, 2))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.seats.length()").value(2))
                .andExpect(jsonPath("$.totalPaise").value(2 * TestFixtures.CLASSIC_PRICE + 2 * 2500))
                .andReturn());
        long holdId = hold.get("holdId").asLong();

        mockMvc.perform(post("/shows/{id}/holds", show.showId()).header("Authorization", bob)
                        .contentType(MediaType.APPLICATION_JSON).content(holdBody(seats.subList(1, 3))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.unavailableSeatIds[0]").value(seats.get(1)));
        mockMvc.perform(get("/shows/{id}/seats", show.showId()))
                .andExpect(jsonPath("$.available").value(4));

        JsonNode payment = json(mockMvc.perform(post("/holds/{id}/payments", holdId).header("Authorization", alice))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn());
        String ref = payment.get("gatewayRef").asText();
        assertThat(payment.get("checkoutPath").asText()).isEqualTo("/pay/" + ref);

        mockMvc.perform(post("/mock-gateway/payments/{ref}", ref).header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"SUCCESS\"}"))
                .andExpect(status().isAccepted());

        // The gateway delivers the signed webhook to this server over HTTP; poll like the client does
        String reference = await().atMost(Duration.ofSeconds(20)).until(
                () -> json(mockMvc.perform(get("/holds/{id}", holdId).header("Authorization", alice)).andReturn())
                        .path("bookingReference").asText(null),
                r -> r != null);

        mockMvc.perform(get("/holds/{id}", holdId).header("Authorization", alice))
                .andExpect(jsonPath("$.status").value("CONVERTED"))
                .andExpect(jsonPath("$.latestPayment.status").value("SUCCEEDED"));
        mockMvc.perform(get("/bookings/me").header("Authorization", alice))
                .andExpect(jsonPath("$.content[0].reference").value(reference))
                .andExpect(jsonPath("$.content[0].status").value("CONFIRMED"));
        assertThat(jdbc.queryForObject("SELECT status FROM mock_gateway_events WHERE gateway_ref = ?", String.class, ref))
                .isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM show_seats WHERE show_id = ? AND status = 'BOOKED'",
                Long.class, show.showId())).isEqualTo(2);
    }

    @Test
    @DisplayName("a declined payment keeps the hold, and the customer can pay again")
    void declinedPayment_canBeRetried() throws Exception {
        ShowFixture show = fixtures.show(1, 4);
        String alice = bearer(fixtures.customer().accessToken());
        long holdId = json(mockMvc.perform(post("/shows/{id}/holds", show.showId()).header("Authorization", alice)
                .contentType(MediaType.APPLICATION_JSON).content(holdBody(show.seatIds().subList(0, 1))))
                .andExpect(status().isCreated()).andReturn()).get("holdId").asLong();
        String ref = json(mockMvc.perform(post("/holds/{id}/payments", holdId).header("Authorization", alice))
                .andReturn()).get("gatewayRef").asText();

        mockMvc.perform(post("/mock-gateway/payments/{ref}", ref).header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"FAILURE\"}"))
                .andExpect(status().isAccepted());
        await().atMost(Duration.ofSeconds(20)).until(() -> "FAILED".equals(json(mockMvc.perform(
                get("/holds/{id}", holdId).header("Authorization", alice)).andReturn()).at("/latestPayment/status").asText()));

        mockMvc.perform(get("/holds/{id}", holdId).header("Authorization", alice))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(post("/holds/{id}/payments", holdId).header("Authorization", alice))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.gatewayRef").value(org.hamcrest.Matchers.not(ref)));
    }

    @Test
    @DisplayName("a retried hold request with the same Idempotency-Key holds once and replays the response")
    void idempotencyKey_preventsDuplicateHolds() throws Exception {
        ShowFixture show = fixtures.show(1, 6);
        String token = bearer(fixtures.customer().accessToken());
        String key = UUID.randomUUID().toString();
        String body = holdBody(show.seatIds().subList(0, 2));

        MvcResult first = mockMvc.perform(post("/shows/{id}/holds", show.showId()).header("Authorization", token)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult retry = mockMvc.perform(post("/shows/{id}/holds", show.showId()).header("Authorization", token)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn();

        assertThat(retry.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seat_holds WHERE show_id = ?", Long.class, show.showId()))
                .isEqualTo(1);
        mockMvc.perform(post("/shows/{id}/holds", show.showId()).header("Authorization", token)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(show.seatIds().subList(3, 4))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("an admin can't schedule two overlapping shows on one screen (409 from the exclusion constraint)")
    void overlappingShow_isRejected() throws Exception {
        ShowFixture show = fixtures.show(1, 4);
        JsonNode existing = json(mockMvc.perform(get("/shows/{id}", show.showId())).andReturn());
        String overlapping = objectMapper.writeValueAsString(Map.of(
                "movieId", existing.at("/movie/id").asLong(),
                "screenId", show.screen().screenId(),
                "startsAt", existing.get("startsAt").asText().replace(":00:00Z", ":30:00Z"),
                "format", "2D",
                "pricesPaise", Map.of("CLASSIC", 15000)));
        mockMvc.perform(post("/admin/shows").header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content(overlapping))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("already has a show")));
    }

    /** A token for the admin the dev profile seeds (not via /auth/login, which other tests rate-limit). */
    private String adminToken() {
        User admin = users.findByEmail("admin@seatsure.dev").orElseThrow();
        return jwtUtil.generateAccessToken(admin.getId(), admin.getEmail(), admin.getRole());
    }

    private String holdBody(List<Long> seatIds) throws Exception {
        return objectMapper.writeValueAsString(Map.of("seatIds", seatIds));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
