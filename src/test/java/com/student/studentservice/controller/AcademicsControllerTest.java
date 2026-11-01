package com.student.studentservice.controller;

import com.student.studentservice.config.AppConfig;
import com.student.studentservice.demo.DemoController;
import com.student.studentservice.demo.DemoDataService;
import com.student.studentservice.dto.CourseDtos.CourseResponse;
import com.student.studentservice.dto.CourseDtos.CourseSummary;
import com.student.studentservice.dto.EnrollmentDtos.AttendanceSummary;
import com.student.studentservice.dto.EnrollmentDtos.MyEnrollment;
import com.student.studentservice.entity.User;
import com.student.studentservice.exception.ConflictException;
import com.student.studentservice.repository.RefreshTokenRepository;
import com.student.studentservice.repository.UserRepository;
import com.student.studentservice.service.*;
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

import java.util.List;
import java.util.Optional;

import static com.student.studentservice.controller.JwtTestTokens.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({CourseController.class, AcademicsController.class, DemoController.class})
@Import({AppConfig.class, JwtUtil.class})
@TestPropertySource(properties = {
        JWT_PROPERTIES_SECRET,
        "jwt.access.expiration=900000",
        "jwt.refresh.expiration=604800000"
})
class AcademicsControllerTest {

    private static final String COURSE_JSON = """
            {"code":"CS201","title":"Data Structures","credits":4,"department":"Computer Science",
             "term":"Autumn 2026","capacity":40}""";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;

    @MockitoBean private CourseService courseService;
    @MockitoBean private EnrollmentService enrollmentService;
    @MockitoBean private AttendanceService attendanceService;
    @MockitoBean private AnnouncementService announcementService;
    @MockitoBean private DashboardService dashboardService;
    @MockitoBean private DemoDataService demoDataService;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private RefreshTokenRepository refreshTokenRepository;

    private static CourseResponse course() {
        return new CourseResponse(1L, "CS201", "Data Structures", null, 4, "Computer Science", "Autumn 2026",
                40, 10, 30, null, null, true);
    }

    @Nested
    @DisplayName("access rules")
    class Access {

        @Test
        void catalog_isReadableByStudentsAndAdmins() throws Exception {
            when(courseService.list(any(), any(), any())).thenReturn(List.of(course()));
            mockMvc.perform(get("/courses").header("Authorization", student(jwtUtil))).andExpect(status().isOk());
            mockMvc.perform(get("/courses/1").header("Authorization", admin(jwtUtil))).andExpect(status().isOk());
        }

        @Test
        void catalog_requiresSignIn() throws Exception {
            mockMvc.perform(get("/courses")).andExpect(status().isForbidden());
        }

        @Test
        void students_cannotManageCourses() throws Exception {
            String token = student(jwtUtil);
            mockMvc.perform(post("/courses").header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON).content(COURSE_JSON)).andExpect(status().isForbidden());
            mockMvc.perform(delete("/courses/1").header("Authorization", token)).andExpect(status().isForbidden());
            mockMvc.perform(get("/courses/1/roster").header("Authorization", token)).andExpect(status().isForbidden());
            mockMvc.perform(get("/courses/1/attendance").header("Authorization", token)).andExpect(status().isForbidden());
            mockMvc.perform(put("/enrollments/1/grade").header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"grade\":\"O\"}")).andExpect(status().isForbidden());
            mockMvc.perform(post("/announcements").header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\",\"body\":\"y\"}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/dashboard/admin").header("Authorization", token)).andExpect(status().isForbidden());
            verifyNoInteractions(courseService, enrollmentService, attendanceService, announcementService, dashboardService);
        }

        @Test
        void admins_cannotUseStudentSelfService() throws Exception {
            String token = admin(jwtUtil);
            mockMvc.perform(post("/courses/1/enroll").header("Authorization", token)).andExpect(status().isForbidden());
            mockMvc.perform(get("/students/me/academics").header("Authorization", token)).andExpect(status().isForbidden());
            mockMvc.perform(get("/dashboard/student").header("Authorization", token)).andExpect(status().isForbidden());
        }

        @Test
        void announcements_feedIsReadableByEveryone() throws Exception {
            mockMvc.perform(get("/announcements").header("Authorization", student(jwtUtil))).andExpect(status().isOk());
            verify(announcementService).feed(STUDENT_EMAIL, false, 50);
            mockMvc.perform(get("/announcements").header("Authorization", admin(jwtUtil))).andExpect(status().isOk());
            verify(announcementService).feed(ADMIN_EMAIL, true, 50);
        }
    }

    @Nested
    @DisplayName("endpoints")
    class Endpoints {

        @Test
        void createCourse_returns201() throws Exception {
            when(courseService.create(any())).thenReturn(course());
            mockMvc.perform(post("/courses").header("Authorization", admin(jwtUtil))
                            .contentType(MediaType.APPLICATION_JSON).content(COURSE_JSON))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.seatsLeft").value(30));
        }

        @Test
        void createCourse_validatesInput() throws Exception {
            mockMvc.perform(post("/courses").header("Authorization", admin(jwtUtil))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"bad code!\",\"title\":\"\",\"credits\":9,\"capacity\":0}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.code").exists())
                    .andExpect(jsonPath("$.errors.title").exists())
                    .andExpect(jsonPath("$.errors.credits").exists())
                    .andExpect(jsonPath("$.errors.capacity").exists())
                    .andExpect(jsonPath("$.errors.department").exists());
            verifyNoInteractions(courseService);
        }

        @Test
        void enroll_usesTheStudentFromTheToken() throws Exception {
            when(enrollmentService.enroll(STUDENT_EMAIL, 1L)).thenReturn(new MyEnrollment(5L,
                    new CourseSummary(1L, "CS201", "Data Structures", 4, "Autumn 2026", null, null),
                    EnrollmentStatus.ENROLLED, null, null, null, AttendanceSummary.empty()));

            mockMvc.perform(post("/courses/1/enroll").header("Authorization", student(jwtUtil)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.course.code").value("CS201"));
        }

        @Test
        void enroll_fullCourse_returns409() throws Exception {
            when(enrollmentService.enroll(any(), any())).thenThrow(new ConflictException("CS201 is full"));
            mockMvc.perform(post("/courses/1/enroll").header("Authorization", student(jwtUtil)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("CS201 is full"));
        }

        @Test
        void drop_returns204() throws Exception {
            mockMvc.perform(delete("/courses/1/enroll").header("Authorization", student(jwtUtil)))
                    .andExpect(status().isNoContent());
            verify(enrollmentService).drop(STUDENT_EMAIL, 1L);
        }

        @Test
        void grade_acceptsLabels_andRejectsUnknownGrades() throws Exception {
            mockMvc.perform(put("/enrollments/5/grade").header("Authorization", admin(jwtUtil))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"grade\":\"A+\"}")).andExpect(status().isOk());
            verify(enrollmentService).assignGrade(5L, Grade.A_PLUS);

            mockMvc.perform(put("/enrollments/5/grade").header("Authorization", admin(jwtUtil))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"grade\":\"Z\"}")).andExpect(status().isBadRequest());
        }

        @Test
        void attendance_futureDate_rejected() throws Exception {
            mockMvc.perform(post("/courses/1/attendance").header("Authorization", admin(jwtUtil))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"date\":\"2999-01-01\",\"records\":[]}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.date").exists());
            verifyNoInteractions(attendanceService);
        }
    }

    @Nested
    @DisplayName("demo login")
    class Demo {

        @Test
        void status_isPublic() throws Exception {
            when(demoDataService.isEnabled()).thenReturn(true);
            mockMvc.perform(get("/auth/demo")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        }

        @Test
        void demoLogin_disabled_returns404() throws Exception {
            when(demoDataService.isEnabled()).thenReturn(false);
            mockMvc.perform(post("/auth/demo-login").contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}"))
                    .andExpect(status().isNotFound());
            verifyNoInteractions(userRepository);
        }

        @Test
        void demoLogin_issuesTokensForTheDemoAccount() throws Exception {
            User demoStudent = new User();
            demoStudent.setEmail("demo.student@studenthub.dev");
            demoStudent.setRole(Roles.ROLE_STUDENT);
            when(demoDataService.isEnabled()).thenReturn(true);
            when(demoDataService.demoStudentEmail()).thenReturn("demo.student@studenthub.dev");
            when(userRepository.findByEmail("demo.student@studenthub.dev")).thenReturn(Optional.of(demoStudent));

            mockMvc.perform(post("/auth/demo-login").contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"STUDENT\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.role").value("ROLE_STUDENT"))
                    .andExpect(jsonPath("$.isFirstTimeLogin").value(false))
                    .andExpect(jsonPath("$.accessToken").isNotEmpty());
            verify(refreshTokenRepository).save(any());
        }
    }
}
