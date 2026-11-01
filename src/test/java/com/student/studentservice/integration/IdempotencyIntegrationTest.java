package com.student.studentservice.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.student.studentservice.repository.StudentRepository;
import com.student.studentservice.service.Roles;
import com.student.studentservice.support.FullAppIntegrationTest;
import com.student.studentservice.support.TestStudents;
import com.student.studentservice.util.JwtUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Idempotency-Key over HTTP, through the real filter chain and database. */
@FullAppIntegrationTest
class IdempotencyIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private StudentRepository studentRepository;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("POST /students retried with the same Idempotency-Key creates one student")
    void idempotencyKey_preventsDuplicateRegistrations() throws Exception {
        String token = "Bearer " + jwtUtil.generateAccessToken("admin@college.com", Roles.ROLE_ADMIN, false);
        String key = UUID.randomUUID().toString();
        String body = objectMapper.writeValueAsString(TestStudents.request(TestStudents.unique("idem") + "@college.test"));
        long before = studentRepository.count();

        MvcResult first = mockMvc.perform(post("/students").header("Authorization", token)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        MvcResult retry = mockMvc.perform(post("/students").header("Authorization", token)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn();

        assertThat(retry.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(retry.getResponse().getHeader("Location")).isEqualTo(first.getResponse().getHeader("Location"));
        assertThat(studentRepository.count()).isEqualTo(before + 1);

        String otherBody = objectMapper.writeValueAsString(TestStudents.request(TestStudents.unique("other") + "@college.test"));
        mockMvc.perform(post("/students").header("Authorization", token)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(otherBody))
                .andExpect(status().isUnprocessableEntity());

        String stored = jdbc.queryForObject("SELECT response_body FROM idempotency_keys WHERE idempotency_key = ?",
                String.class, key);
        assertThat(stored).as("stored response (with the temp password) is encrypted").doesNotContain("temporaryPassword");
    }
}
