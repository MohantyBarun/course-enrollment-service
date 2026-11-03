package com.seatsure.integration;

import com.seatsure.service.Roles;
import com.seatsure.support.FullAppIntegrationTest;
import com.seatsure.util.JwtUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The admin insight endpoints against the real application context (real caches, metrics and breakers). */
@FullAppIntegrationTest
class InsightsIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;

    private String admin() {
        return "Bearer " + jwtUtil.generateAccessToken(1L, "admin@seatsure.dev", Roles.ROLE_ADMIN);
    }

    @Test
    @DisplayName("system snapshot reflects the real caches, circuit breakers and infrastructure")
    void systemSnapshot() throws Exception {
        mockMvc.perform(get("/cities")).andExpect(status().isOk());

        mockMvc.perform(get("/admin/system").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.infrastructure.cache").value("Caffeine (in-process)"))
                .andExpect(jsonPath("$.infrastructure.email").value("Logged only (SMTP not configured)"))
                .andExpect(jsonPath("$.caches[*].name", hasItem("nowShowing")))
                .andExpect(jsonPath("$.circuitBreakers[*].name", hasItem("smtp")))
                .andExpect(jsonPath("$.rateLimits.limitsPerMinute.login").value(3))
                .andExpect(jsonPath("$.http.requests").isNumber());
    }

    @Test
    @DisplayName("notification feed is available to admins")
    void notificationFeed() throws Exception {
        mockMvc.perform(get("/admin/notifications").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transport").value("In-process"))
                .andExpect(jsonPath("$.entries").isArray());
    }
}
