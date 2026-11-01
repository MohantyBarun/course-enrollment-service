package com.student.studentservice.controller;

import com.student.studentservice.config.AppConfig;
import com.student.studentservice.insights.NotificationFeedService;
import com.student.studentservice.insights.SystemStatusService;
import com.student.studentservice.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static com.student.studentservice.controller.JwtTestTokens.*;
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
    @MockitoBean private com.student.studentservice.insights.TestEmailService testEmail;

    @Test
    void admin_canQueueATestEmail_studentsCannot() throws Exception {
        when(testEmail.queue(null, ADMIN_EMAIL))
                .thenReturn(new com.student.studentservice.insights.TestEmailService.Queued("ow***@gmail.com", "queued"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/admin/notifications/test")
                        .header("Authorization", admin(jwtUtil)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.recipient").value("ow***@gmail.com"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/admin/notifications/test")
                        .header("Authorization", student(jwtUtil)))
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
    void students_andAnonymousUsers_areRejected() throws Exception {
        mockMvc.perform(get("/admin/notifications").header("Authorization", student(jwtUtil))).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/system").header("Authorization", student(jwtUtil))).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/system")).andExpect(status().isForbidden());
    }
}
