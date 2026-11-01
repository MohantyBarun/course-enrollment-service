package com.seatsure.integration;

import com.seatsure.support.FullAppIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The rate limiter inside the real security filter chain (login limit is 3/min in these tests). */
@FullAppIntegrationTest
class RateLimitIntegrationTest {

    private static final String BODY = "{\"email\":\"nobody@seatsure.test\",\"password\":\"Wrong@123\"}";

    @Autowired private MockMvc mockMvc;

    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    @Test
    @DisplayName("repeated logins from one address get 429 with Retry-After; other clients are unaffected")
    void loginAttempts_areRateLimited() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/auth/login").with(from("203.0.113.7"))
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().is4xxClientError())
                    .andExpect(header().exists("X-RateLimit-Remaining"));
        }
        mockMvc.perform(post("/auth/login").with(from("203.0.113.7"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        mockMvc.perform(post("/auth/login").with(from("203.0.113.8"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized()); // not rate limited: the usual answer for an unknown account
    }
}
