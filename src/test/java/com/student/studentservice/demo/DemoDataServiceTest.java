package com.student.studentservice.demo;

import com.student.studentservice.config.JpaAuditingConfig;
import com.student.studentservice.entity.Persons;
import com.student.studentservice.repository.*;
import com.student.studentservice.service.EnrollmentStatus;
import com.student.studentservice.service.Roles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.student.studentservice.support.PostgresTestcontainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the real demo seeding and reset against PostgreSQL (Testcontainers; skipped without Docker). */
@DataJpaTest(properties = "app.demo.enabled=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Import({DemoDataService.class, JpaAuditingConfig.class, DemoDataServiceTest.Config.class, PostgresTestcontainer.class})
class DemoDataServiceTest {

    @TestConfiguration
    static class Config {
        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder(4);
        }
    }

    @Autowired private DemoDataService demo;
    @Autowired private CourseRepository courseRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private EnrollmentRepository enrollmentRepository;
    @Autowired private AttendanceRecordRepository attendanceRepository;
    @Autowired private AnnouncementRepository announcementRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PersonsRepository personsRepository;

    @Test
    void seed_createsAConsistentDemoDataset() {
        demo.seedIfEmpty();

        assertThat(courseRepository.count()).isEqualTo(12);
        assertThat(studentRepository.count()).isEqualTo(36);
        assertThat(enrollmentRepository.countByStatus(EnrollmentStatus.COMPLETED)).isGreaterThan(100);
        assertThat(enrollmentRepository.countByStatus(EnrollmentStatus.ENROLLED)).isGreaterThan(100);
        assertThat(attendanceRepository.count()).isGreaterThan(1000);
        assertThat(announcementRepository.count()).isEqualTo(5);

        // Both demo accounts exist and can be signed into without a password change
        assertThat(userRepository.findByEmail(demo.demoAdminEmail())).get()
                .satisfies(u -> assertThat(u.getRole()).isEqualTo(Roles.ROLE_ADMIN))
                .satisfies(u -> assertThat(u.getIsFirstTimeLogin()).isFalse());
        assertThat(userRepository.findByEmail(demo.demoStudentEmail())).get()
                .satisfies(u -> assertThat(u.getRole()).isEqualTo(Roles.ROLE_STUDENT));

        // Every course stays within capacity
        for (Object[] row : enrollmentRepository.countByCourseGrouped(List.of(EnrollmentStatus.ENROLLED, EnrollmentStatus.COMPLETED))) {
            int capacity = courseRepository.findById((Long) row[0]).orElseThrow().getCapacity();
            assertThat((Long) row[1]).isLessThanOrEqualTo(capacity);
        }
    }

    @Test
    void seed_isIdempotent() {
        demo.seedIfEmpty();
        demo.seedIfEmpty();

        assertThat(courseRepository.count()).isEqualTo(12);
        assertThat(studentRepository.count()).isEqualTo(36);
    }

    @Test
    void reset_replacesStudentDataButKeepsAdmins() {
        Persons realAdmin = personsRepository.save(new Persons(null, "Real Admin", "real.admin@college.com"));
        var adminUser = new com.student.studentservice.entity.User();
        adminUser.setPersons(realAdmin);
        adminUser.setEmail(realAdmin.getEmail());
        adminUser.setRole(Roles.ROLE_ADMIN);
        userRepository.save(adminUser);
        demo.seedIfEmpty();

        demo.reset();

        assertThat(courseRepository.count()).isEqualTo(12);
        assertThat(studentRepository.count()).isEqualTo(36);
        assertThat(announcementRepository.count()).isEqualTo(5);
        assertThat(userRepository.findByEmail("real.admin@college.com")).isPresent();
        assertThat(userRepository.findByEmail(demo.demoAdminEmail())).isPresent();
        assertThat(userRepository.findByEmail(demo.demoStudentEmail())).isPresent();
    }
}
