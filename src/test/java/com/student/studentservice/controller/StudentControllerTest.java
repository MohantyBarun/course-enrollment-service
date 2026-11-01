package com.student.studentservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.student.studentservice.config.AppConfig;
import com.student.studentservice.dto.PagedResponseDTO;
import com.student.studentservice.dto.StudentRequestDTO;
import com.student.studentservice.dto.StudentResponseDTO;
import com.student.studentservice.exception.BadRequestException;
import com.student.studentservice.exception.DuplicateEmailException;
import com.student.studentservice.exception.ResourceNotFoundException;
import com.student.studentservice.service.StudentService;
import com.student.studentservice.util.JwtUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static com.student.studentservice.controller.JwtTestTokens.*;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(StudentController.class)
@Import({AppConfig.class, JwtUtil.class})
@TestPropertySource(properties = {
        JWT_PROPERTIES_SECRET,
        "jwt.access.expiration=900000",
        "jwt.refresh.expiration=604800000"
})
class StudentControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtUtil jwtUtil;

    @MockitoBean private StudentService studentService;

    private static StudentRequestDTO validRequest() {
        StudentRequestDTO dto = new StudentRequestDTO();
        dto.setName("John Doe");
        dto.setEmail("john@x.com");
        dto.setDepartment("Engineering");
        dto.setBranch("CSE");
        dto.setDateOfBirth(LocalDate.of(2003, 5, 14));
        return dto;
    }

    private static StudentResponseDTO response(long id) {
        StudentResponseDTO dto = new StudentResponseDTO();
        dto.setId(id);
        dto.setName("John Doe");
        dto.setEmail("john@x.com");
        return dto;
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("security rules")
    class Security {

        @Test
        void noToken_isRejected() throws Exception {
            mockMvc.perform(get("/students")).andExpect(status().isForbidden());
            verifyNoInteractions(studentService);
        }

        @Test
        void corsPreflight_fromAllowedOrigin_isAccepted() throws Exception {
            mockMvc.perform(options("/students")
                            .header("Origin", "http://localhost:5173")
                            .header("Access-Control-Request-Method", "POST")
                            .header("Access-Control-Request-Headers", "Authorization, Content-Type"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                    .andExpect(header().string("Access-Control-Allow-Methods", containsString("POST")));
        }

        @Test
        void corsPreflight_fromUnknownOrigin_isRejected() throws Exception {
            mockMvc.perform(options("/students")
                            .header("Origin", "https://evil.example.com")
                            .header("Access-Control-Request-Method", "DELETE"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }

        @Test
        void nonBearerAuthorizationHeader_isIgnoredAndRejected() throws Exception {
            mockMvc.perform(get("/students").header("Authorization", "Basic YWRtaW46QWRtaW5AMTIz"))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(studentService);
        }

        @Test
        void invalidToken_isRejected() throws Exception {
            mockMvc.perform(get("/students").header("Authorization", "Bearer not.a.jwt"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void refreshTokenUsedAsAccessToken_isRejected() throws Exception {
            mockMvc.perform(get("/students").header("Authorization", bearer(jwtUtil.generateRefreshToken(ADMIN_EMAIL))))
                    .andExpect(status().isForbidden());
        }

        @Test
        void student_cannotUseAdminEndpoints() throws Exception {
            String token = student(jwtUtil);
            mockMvc.perform(get("/students").header("Authorization", token)).andExpect(status().isForbidden());
            mockMvc.perform(get("/students/1").header("Authorization", token)).andExpect(status().isForbidden());
            mockMvc.perform(delete("/students/1").header("Authorization", token)).andExpect(status().isForbidden());
            mockMvc.perform(post("/students").header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(studentService);
        }

        @Test
        void admin_cannotUseStudentOnlyMeEndpoint() throws Exception {
            mockMvc.perform(get("/students/me").header("Authorization", admin(jwtUtil)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("users on a temporary password are blocked until they change it")
        void restrictedToken_isBlockedFromProfile() throws Exception {
            mockMvc.perform(get("/students/me").header("Authorization", restrictedStudent(jwtUtil)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error", containsString("Password change required")));
            verifyNoInteractions(studentService);
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("endpoints")
    class Endpoints {

        @Test
        void create_returns201WithLocationAndBody() throws Exception {
            when(studentService.registerStudent(any())).thenReturn(response(42));

            mockMvc.perform(post("/students").header("Authorization", admin(jwtUtil))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/students/42")))
                    .andExpect(jsonPath("$.id").value(42))
                    .andExpect(jsonPath("$.email").value("john@x.com"));
        }

        @Test
        void create_invalidBody_returns400WithFieldErrors() throws Exception {
            StudentRequestDTO bad = validRequest();
            bad.setEmail("not-an-email");
            bad.setName("");
            bad.setDateOfBirth(LocalDate.now().plusDays(1));

            mockMvc.perform(post("/students").header("Authorization", admin(jwtUtil))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bad)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.email").exists())
                    .andExpect(jsonPath("$.errors.name").exists())
                    .andExpect(jsonPath("$.errors.dateOfBirth").exists());
            verifyNoInteractions(studentService);
        }

        @Test
        void create_malformedJson_returns400() throws Exception {
            mockMvc.perform(post("/students").header("Authorization", admin(jwtUtil))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{not json"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Malformed request body"));
        }

        @Test
        void create_duplicateEmail_returns409() throws Exception {
            when(studentService.registerStudent(any())).thenThrow(new DuplicateEmailException("john@x.com"));

            mockMvc.perform(post("/students").header("Authorization", admin(jwtUtil))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("a constraint violation from a race (e.g. double submit) is a 409, not a 500")
        void create_constraintViolation_returns409() throws Exception {
            when(studentService.registerStudent(any()))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uk_persons_email"));

            mockMvc.perform(post("/students").header("Authorization", admin(jwtUtil))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value(containsString("try again")));
        }

        @Test
        void responses_carryARequestId() throws Exception {
            mockMvc.perform(get("/students/me").header("Authorization", student(jwtUtil))
                            .header("X-Request-Id", "trace-me-123"))
                    .andExpect(header().string("X-Request-Id", "trace-me-123"));
        }

        @Test
        void list_passesQueryParametersAndDefaults() throws Exception {
            when(studentService.getStudents(any(), any(), any(), anyInt(), anyInt(), any(), any()))
                    .thenReturn(new PagedResponseDTO<>(List.of(response(1)), 0, 10, 1, 1, true));

            mockMvc.perform(get("/students").param("search", "john").param("department", "Engineering")
                            .header("Authorization", admin(jwtUtil)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].id").value(1))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.last").value(true));

            verify(studentService).getStudents("john", "Engineering", null, 0, 10, "id", "asc");
        }

        @Test
        void list_invalidPaging_returns400WithMessage() throws Exception {
            when(studentService.getStudents(any(), any(), any(), anyInt(), anyInt(), any(), any()))
                    .thenThrow(new BadRequestException("Page size must be between 1 and 100"));

            mockMvc.perform(get("/students").param("size", "1000").header("Authorization", admin(jwtUtil)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Page size must be between 1 and 100"));
        }

        @Test
        void unknownEndpoint_returns404NotServerError() throws Exception {
            mockMvc.perform(get("/does-not-exist").header("Authorization", admin(jwtUtil)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value(containsString("Endpoint not found")));
        }

        @Test
        void getById_returnsStudent() throws Exception {
            when(studentService.getStudentById(5L)).thenReturn(response(5));

            mockMvc.perform(get("/students/5").header("Authorization", admin(jwtUtil)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(5));
        }

        @Test
        void getById_unknown_returns404() throws Exception {
            when(studentService.getStudentById(5L)).thenThrow(new ResourceNotFoundException("Student not found with id: 5"));

            mockMvc.perform(get("/students/5").header("Authorization", admin(jwtUtil)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("Student not found with id: 5"));
        }

        @Test
        void getById_nonNumericId_returns400() throws Exception {
            mockMvc.perform(get("/students/abc").header("Authorization", admin(jwtUtil)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void update_returnsUpdatedStudent() throws Exception {
            when(studentService.updateStudent(eq(5L), any())).thenReturn(response(5));

            mockMvc.perform(put("/students/5").header("Authorization", admin(jwtUtil))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(5));
        }

        @Test
        void delete_returns204() throws Exception {
            mockMvc.perform(delete("/students/5").header("Authorization", admin(jwtUtil)))
                    .andExpect(status().isNoContent());
            verify(studentService).deleteStudent(5L);
        }

        @Test
        void unsupportedMethod_returns405() throws Exception {
            mockMvc.perform(patch("/students/5").header("Authorization", admin(jwtUtil)))
                    .andExpect(status().isMethodNotAllowed());
        }

        @Test
        void me_returnsProfileOfAuthenticatedStudent() throws Exception {
            when(studentService.getStudentByEmail(STUDENT_EMAIL)).thenReturn(response(9));

            mockMvc.perform(get("/students/me").header("Authorization", student(jwtUtil)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(9));
        }
    }
}
