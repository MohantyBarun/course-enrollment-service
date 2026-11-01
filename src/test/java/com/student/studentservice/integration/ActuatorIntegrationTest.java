package com.student.studentservice.integration;

import com.student.studentservice.support.FullAppIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Actuator security and the exported metrics (metrics password is set in FullAppIntegrationTest). */
@FullAppIntegrationTest
class ActuatorIntegrationTest {

    @Autowired private MockMvc mockMvc;

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("liveness is public; Prometheus metrics need the metrics user and include business metrics")
    void actuatorEndpoints_areSecuredSeparately() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/prometheus").header("Authorization", basic("metrics", "wrong")))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/actuator/prometheus").header("Authorization", basic("metrics", "test-metrics-password")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("outbox_events_pending")))
                .andExpect(content().string(containsString("http_server_requests_seconds")));
    }
}
