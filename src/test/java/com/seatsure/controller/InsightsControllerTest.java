package com.seatsure.controller;

import com.seatsure.config.AppConfig;
import com.seatsure.demo.DemoPrivacy;
import com.seatsure.ratelimit.ClientIpResolver;
import com.seatsure.service.Roles;
import com.seatsure.insights.NotificationFeedService;
import com.seatsure.insights.SystemStatusService;
import com.seatsure.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.seatsure.controller.JwtTestTokens.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InsightsController.class)
@Import({AppConfig.class, JwtUtil.class, DemoPrivacy.class, InsightsControllerTest.OneProxy.class})
@TestPropertySource(properties = {
        JWT_PROPERTIES_SECRET,
        "jwt.access.expiration=900000",
        "jwt.refresh.expiration=604800000",
        "app.demo.enabled=true"
})
class InsightsControllerTest {

    @TestConfiguration
    static class OneProxy {
        @Bean
        ClientIpResolver clientIpResolver() {
            return new ClientIpResolver(1);
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;

    @MockitoBean private NotificationFeedService notificationFeed;
    @MockitoBean private SystemStatusService systemStatus;
    @MockitoBean private com.seatsure.insights.TestEmailService testEmail;

    @Test
    void admin_canQueueATestEmail_customersCannot() throws Exception {
        when(testEmail.queue(null, "admin@seatsure.test"))
                .thenReturn(new com.seatsure.insights.TestEmailService.Queued("ow***@gmail.com", "queued"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/admin/notifications/test")
                        .header("Authorization", admin(jwtUtil)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.recipient").value("ow***@gmail.com"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/admin/notifications/test")
                        .header("Authorization", customer(jwtUtil)))
                .andExpect(status().isForbidden());
    }

    @Test
    void admin_seesTheNotificationFeed() throws Exception {
        when(notificationFeed.latest()).thenReturn(new NotificationFeedService.Feed("SMTP", "In-process", List.of(), List.of()));

        mockMvc.perform(get("/admin/notifications").header("Authorization", admin(jwtUtil)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailMode").value("SMTP"));
    }

    private static NotificationFeedService.Entry sentTo(String recipient) {
        return new NotificationFeedService.Entry(UUID.randomUUID(), "booking.confirmed", "Booking confirmation", recipient,
                "Your tickets", "Hi, your seats are confirmed.", NotificationFeedService.Status.LOGGED, null, 1,
                Instant.now(), Instant.now(), null);
    }

    @Test
    void demoAdmin_seesRealCustomersMasked_seededOnesAndRealAdminsSeeEverything() throws Exception {
        when(notificationFeed.latest()).thenReturn(new NotificationFeedService.Feed("Logged only", "In-process", List.of(),
                List.of(sentTo("asha.real@gmail.com"), sentTo("riya.sen@seatsure.dev"))));
        String demoAdmin = "Bearer " + jwtUtil.generateAccessToken(99L, "demo.admin@seatsure.dev", Roles.ROLE_ADMIN);

        mockMvc.perform(get("/admin/notifications").header("Authorization", demoAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].recipient").value("as***@gmail.com"))
                .andExpect(jsonPath("$.entries[0].preview").value("(hidden on the public demo)"))
                .andExpect(jsonPath("$.entries[1].recipient").value("riya.sen@seatsure.dev"))
                .andExpect(jsonPath("$.entries[1].preview").value("Hi, your seats are confirmed."));
        mockMvc.perform(get("/admin/notifications").header("Authorization", admin(jwtUtil)))
                .andExpect(jsonPath("$.entries[0].recipient").value("asha.real@gmail.com"));
    }

    @Test
    void clientIpDiagnostic_marksTheEntryOurProxyAdded_notTheForgedOne() throws Exception {
        mockMvc.perform(get("/admin/system/client-ip").header("Authorization", admin(jwtUtil))
                        .header("X-Forwarded-For", "1.2.3.4, 198.51.100.7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trustedProxies").value(1))
                .andExpect(jsonPath("$.chain[0].address").value("1.2.3.x"))
                .andExpect(jsonPath("$.chain[0].client").value(false))
                .andExpect(jsonPath("$.chain[1].client").value(true));
        mockMvc.perform(get("/admin/system/client-ip").header("Authorization", customer(jwtUtil)))
                .andExpect(status().isForbidden());
    }

    @Test
    void customers_andAnonymousUsers_areRejected() throws Exception {
        mockMvc.perform(get("/admin/notifications").header("Authorization", customer(jwtUtil))).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/system").header("Authorization", customer(jwtUtil))).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/system")).andExpect(status().isUnauthorized());
    }
}
