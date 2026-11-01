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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The booking flow over HTTP: public browsing, auth rules, 409 details and idempotent retries. */
@FullAppIntegrationTest
class BookingApiIntegrationTest {

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
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.rows[0].label").value("A"));
    }

    @Test
    @DisplayName("booking needs a customer token: 401 without one, 403 for admins on customer routes")
    void booking_requiresCustomer() throws Exception {
        ShowFixture show = fixtures.show(1, 4);
        String body = bookBody(show.seatIds().subList(0, 1));
        mockMvc.perform(post("/shows/{id}/bookings", show.showId()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        String customer = bearer(fixtures.customer().accessToken());
        mockMvc.perform(get("/admin/dashboard").header("Authorization", customer))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("book, see it in my bookings, lose a race for a taken seat (409 names it), cancel")
    void bookingFlow() throws Exception {
        ShowFixture show = fixtures.show(1, 6);
        List<Long> seats = show.seatIds();
        String alice = bearer(fixtures.customer().accessToken());
        String bob = bearer(fixtures.customer().accessToken());

        MvcResult booked = mockMvc.perform(post("/shows/{id}/bookings", show.showId()).header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON).content(bookBody(seats.subList(0, 2))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.seats.length()").value(2))
                .andExpect(jsonPath("$.ticketsPaise").value(2 * TestFixtures.CLASSIC_PRICE))
                .andExpect(jsonPath("$.cancellable").value(true))
                .andReturn();
        String reference = json(booked).get("reference").asText();
        assertThat(booked.getResponse().getHeader("Location")).endsWith("/bookings/" + reference);

        mockMvc.perform(post("/shows/{id}/bookings", show.showId()).header("Authorization", bob)
                        .contentType(MediaType.APPLICATION_JSON).content(bookBody(seats.subList(1, 3))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.unavailableSeatIds.length()").value(1))
                .andExpect(jsonPath("$.unavailableSeatIds[0]").value(seats.get(1)));

        mockMvc.perform(get("/bookings/me").header("Authorization", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].reference").value(reference));
        mockMvc.perform(get("/bookings/{ref}", reference).header("Authorization", bob))
                .andExpect(status().isNotFound()); // someone else's booking doesn't exist for you

        mockMvc.perform(post("/bookings/{ref}/cancel", reference).header("Authorization", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(get("/shows/{id}/seats", show.showId()))
                .andExpect(jsonPath("$.available").value(6));
    }

    @Test
    @DisplayName("a retried booking with the same Idempotency-Key books once and replays the response")
    void idempotencyKey_preventsDoubleBooking() throws Exception {
        ShowFixture show = fixtures.show(1, 6);
        String token = bearer(fixtures.customer().accessToken());
        String key = UUID.randomUUID().toString();
        String body = bookBody(show.seatIds().subList(0, 2));

        MvcResult first = mockMvc.perform(post("/shows/{id}/bookings", show.showId()).header("Authorization", token)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult retry = mockMvc.perform(post("/shows/{id}/bookings", show.showId()).header("Authorization", token)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn();

        assertThat(retry.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bookings WHERE show_id = ?", Long.class, show.showId()))
                .isEqualTo(1);
        mockMvc.perform(post("/shows/{id}/bookings", show.showId()).header("Authorization", token)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content(bookBody(show.seatIds().subList(3, 4))))
                .andExpect(status().isUnprocessableEntity());
        String stored = jdbc.queryForObject("SELECT response_body FROM idempotency_keys WHERE idempotency_key = ?",
                String.class, key);
        assertThat(stored).as("stored response is encrypted").doesNotContain("reference");
    }

    @Test
    @DisplayName("an admin can't schedule two overlapping shows on one screen (409 from the exclusion constraint)")
    void overlappingShow_isRejected() throws Exception {
        ShowFixture show = fixtures.show(1, 4);
        String admin = bearer(adminToken());
        JsonNode existing = json(mockMvc.perform(get("/shows/{id}", show.showId())).andReturn());
        String overlapping = objectMapper.writeValueAsString(Map.of(
                "movieId", existing.at("/movie/id").asLong(),
                "screenId", show.screen().screenId(),
                "startsAt", existing.get("startsAt").asText().replace(":00:00Z", ":30:00Z"),
                "format", "2D",
                "pricesPaise", Map.of("CLASSIC", 15000)));
        mockMvc.perform(post("/admin/shows").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(overlapping))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("already has a show")));
    }

    /** A token for the admin the dev profile seeds (not via /auth/login, which other tests rate-limit). */
    private String adminToken() {
        User admin = users.findByEmail("admin@seatsure.dev").orElseThrow();
        return jwtUtil.generateAccessToken(admin.getId(), admin.getEmail(), admin.getRole());
    }

    private String bookBody(List<Long> seatIds) throws Exception {
        return objectMapper.writeValueAsString(Map.of("seatIds", seatIds));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
