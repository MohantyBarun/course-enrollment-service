package com.student.studentservice.integration;

import com.student.studentservice.dto.CourseDtos.CourseRequest;
import com.student.studentservice.notification.EmailMessage;
import com.student.studentservice.notification.EmailSender;
import com.student.studentservice.outbox.OutboxEvents;
import com.student.studentservice.repository.OutboxEventRepository;
import com.student.studentservice.repository.StudentRepository;
import com.student.studentservice.service.CourseService;
import com.student.studentservice.service.EnrollmentService;
import com.student.studentservice.service.StudentService;
import com.student.studentservice.support.FullAppIntegrationTest;
import com.student.studentservice.support.TestStudents;
import com.student.studentservice.insights.NotificationFeedService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/** The transactional outbox with the in-process transport, against PostgreSQL. */
@FullAppIntegrationTest
class OutboxIntegrationTest {

    @Autowired private StudentService studentService;
    @Autowired private CourseService courseService;
    @Autowired private EnrollmentService enrollmentService;
    @Autowired private StudentRepository studentRepository;
    @Autowired private OutboxEventRepository outboxRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactionTemplate;

    @Autowired private NotificationFeedService notificationFeed;

    @MockitoBean private EmailSender emailSender;

    @BeforeEach
    void smtpAcceptsEverything() {
        when(emailSender.send(any())).thenReturn(EmailSender.Delivery.sent("Accepted by the SMTP server"));
        when(emailSender.mode()).thenReturn("SMTP");
    }

    @Test
    @DisplayName("registration and enrollment events go through the outbox and send each email once")
    void outbox_deliversEmailsAfterCommit() {
        String email = TestStudents.unique("outbox") + "@college.test";
        studentService.registerStudent(TestStudents.request(email));
        String code = TestStudents.unique("O").toUpperCase().replace("-", "").substring(0, 9);
        Long courseId = courseService.create(new CourseRequest(code, "Outbox course", null, 3, "Computer Science",
                "Autumn 2026", 10, null, null, true)).id();
        enrollmentService.enroll(email, courseId);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            verify(emailSender, times(1)).send(argThat((EmailMessage m) ->
                    m.to().equals(email) && m.subject().equals("Your StudentHub account")
                            && m.body().contains("Temporary password: ")));
            verify(emailSender, times(1)).send(argThat((EmailMessage m) ->
                    m.to().equals(email) && m.subject().startsWith("Enrolled: ")));
        });
        Long notPublished = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE status <> 'PUBLISHED' "
                + "AND payload LIKE ?", Long.class, "%" + email + "%");
        assertThat(notPublished).isZero();
        assertThat(jdbc.queryForObject("SELECT payload FROM outbox_events WHERE event_type = ? AND payload LIKE ?",
                String.class, OutboxEvents.STUDENT_REGISTERED, "%" + email + "%"))
                .as("no plain-text password in the outbox").contains("encryptedTempPassword")
                .doesNotContain("Temporary password");

        var entries = notificationFeed.latest().entries().stream().filter(e -> email.equals(e.recipient())).toList();
        assertThat(entries).hasSize(2).allSatisfy(e -> assertThat(e.status()).isEqualTo(NotificationFeedService.Status.SENT));
        assertThat(entries).filteredOn(e -> e.eventType().equals(OutboxEvents.STUDENT_REGISTERED)).singleElement()
                .satisfies(e -> assertThat(e.preview()).contains("Temporary password: •••"));
    }

    @Test
    @DisplayName("a rolled-back registration leaves no outbox event, so no email is ever sent")
    void rolledBackChange_hasNoEvent() {
        String email = TestStudents.unique("rollback") + "@college.test";
        long before = outboxRepository.count();

        transactionTemplate.executeWithoutResult(tx -> {
            studentService.registerStudent(TestStudents.request(email)); // joins this transaction
            tx.setRollbackOnly();
        });

        assertThat(outboxRepository.count()).isEqualTo(before);
        assertThat(studentRepository.findAll()).noneMatch(s -> s.getPersons().getEmail().equals(email));
        verify(emailSender, after(2000).never()).send(argThat((EmailMessage m) -> m.to().equals(email)));
    }
}
