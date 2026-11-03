package com.seatsure.controller;

import com.seatsure.config.AppConfig;
import com.seatsure.insights.NotificationFeedService;
import com.seatsure.insights.SystemStatusService;
import com.seatsure.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static com.seatsure.controller.JwtTestTokens.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InsightsController.class)
@Import({AppConfig.class, JwtUtil.class})
@TestPropertySource(properties = {
        JWT_PROPERTIES_SECRET,
        "jwt.access.expiration=900000",
        "jwt.refresh.expiration=604800000"
})
class InsightsControllerTest {

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

    @Test
    void customers_andAnonymousUsers_areRejected() throws Exception {
        mockMvc.perform(get("/admin/notifications").header("Authorization", customer(jwtUtil))).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/system").header("Authorization", customer(jwtUtil))).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/system")).andExpect(status().isUnauthorized());
    }
}
