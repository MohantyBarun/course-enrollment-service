package com.student.studentservice.service;

import com.student.studentservice.dto.EnrollmentDtos.AcademicRecord;
import com.student.studentservice.dto.EnrollmentDtos.MyEnrollment;
import com.student.studentservice.entity.Course;
import com.student.studentservice.entity.Enrollment;
import com.student.studentservice.entity.Student;
import com.student.studentservice.exception.BadRequestException;
import com.student.studentservice.exception.ConflictException;
import com.student.studentservice.exception.ResourceNotFoundException;
import com.student.studentservice.repository.AttendanceRecordRepository;
import com.student.studentservice.repository.EnrollmentRepository;
import com.student.studentservice.repository.StudentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static com.student.studentservice.service.AcademicTestData.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EnrollmentServiceTest {

    private static final String EMAIL = "asha.rao@x.com";
    private static final String TERM = "Autumn 2026";

    @Mock private EnrollmentRepository enrollmentRepository;
    @Mock private AttendanceRecordRepository attendanceRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private CourseService courseService;
    @Mock private CurrentStudentResolver currentStudent;
    @Mock private com.student.studentservice.outbox.OutboxService outbox;

    private EnrollmentService service;
    private Student student;
    private Course course;

    @BeforeEach
    void setUp() {
        service = new EnrollmentService(enrollmentRepository, attendanceRepository, studentRepository,
                courseService, currentStudent, outbox);
        student = student(1, "Asha Rao");
        course = course(10, "CS201", 4, 30, TERM, true);
    }

    @Nested
    @DisplayName("enroll")
    class Enroll {

        @BeforeEach
        void givenStudentAndCourse() {
            when(currentStudent.require(EMAIL)).thenReturn(student);
            when(studentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(student));
            when(courseService.findForUpdate(10L)).thenReturn(course);
        }

        private void givenSeatsTakenAndCredits(long seats, long credits) {
            when(enrollmentRepository.countByCourseIdAndStatusIn(eq(10L), anyCollection())).thenReturn(seats);
            when(enrollmentRepository.sumCredits(1L, TERM, EnrollmentStatus.ENROLLED)).thenReturn(credits);
        }

        @Test
        void createsEnrollment() {
            when(enrollmentRepository.findByStudentIdAndCourseId(1L, 10L)).thenReturn(Optional.empty());
            givenSeatsTakenAndCredits(5, 8);
            when(enrollmentRepository.save(any(Enrollment.class))).thenAnswer(inv -> {
                Enrollment e = inv.getArgument(0);
                e.setId(99L);
                return e;
            });

            MyEnrollment result = service.enroll(EMAIL, 10L);

            assertThat(result.id()).isEqualTo(99L);
            assertThat(result.status()).isEqualTo(EnrollmentStatus.ENROLLED);
            assertThat(result.course().code()).isEqualTo("CS201");
        }

        @Test
        @DisplayName("locks the student row, then the course row, before checking seats and credits")
        void locksStudentThenCourse() {
            when(enrollmentRepository.findByStudentIdAndCourseId(1L, 10L)).thenReturn(Optional.empty());
            givenSeatsTakenAndCredits(5, 8);
            when(enrollmentRepository.save(any(Enrollment.class))).thenAnswer(inv -> inv.getArgument(0));

            service.enroll(EMAIL, 10L);

            var order = inOrder(studentRepository, courseService, enrollmentRepository);
            order.verify(studentRepository).findByIdForUpdate(1L);
            order.verify(courseService).findForUpdate(10L);
            order.verify(enrollmentRepository).countByCourseIdAndStatusIn(eq(10L), anyCollection());
            verify(courseService, never()).find(anyLong());
        }

        @Test
        @DisplayName("records an enrollment-confirmed event in the outbox")
        void recordsOutboxEvent() {
            when(enrollmentRepository.findByStudentIdAndCourseId(1L, 10L)).thenReturn(Optional.empty());
            givenSeatsTakenAndCredits(5, 8);
            when(enrollmentRepository.save(any(Enrollment.class))).thenAnswer(inv -> {
                Enrollment e = inv.getArgument(0);
                e.setId(99L);
                return e;
            });

            service.enroll(EMAIL, 10L);

            var payload = org.mockito.ArgumentCaptor.forClass(
                    com.student.studentservice.outbox.OutboxEvents.EnrollmentConfirmed.class);
            verify(outbox).record(eq("Enrollment"), eq(99L),
                    eq(com.student.studentservice.outbox.OutboxEvents.ENROLLMENT_CONFIRMED), payload.capture());
            assertThat(payload.getValue().courseCode()).isEqualTo("CS201");
            assertThat(payload.getValue().studentId()).isEqualTo(1L);
        }

        @Test
        void rejectedEnrollment_recordsNoEvent() {
            course.setEnrollmentOpen(false);

            assertThatThrownBy(() -> service.enroll(EMAIL, 10L)).isInstanceOf(BadRequestException.class);
            verifyNoInteractions(outbox);
        }

        @Test
        void closedCourse_rejected() {
            course.setEnrollmentOpen(false);

            assertThatThrownBy(() -> service.enroll(EMAIL, 10L))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("closed");
            verify(enrollmentRepository, never()).save(any());
        }

        @Test
        void alreadyEnrolled_conflict() {
            when(enrollmentRepository.findByStudentIdAndCourseId(1L, 10L))
                    .thenReturn(Optional.of(enrollment(5, student, course, EnrollmentStatus.ENROLLED, null)));

            assertThatThrownBy(() -> service.enroll(EMAIL, 10L))
                    .isInstanceOf(ConflictException.class).hasMessageContaining("already enrolled");
        }

        @Test
        void alreadyCompleted_conflict() {
            when(enrollmentRepository.findByStudentIdAndCourseId(1L, 10L))
                    .thenReturn(Optional.of(enrollment(5, student, course, EnrollmentStatus.COMPLETED, Grade.A)));

            assertThatThrownBy(() -> service.enroll(EMAIL, 10L))
                    .isInstanceOf(ConflictException.class).hasMessageContaining("completed");
        }

        @Test
        void fullCourse_conflict() {
            when(enrollmentRepository.findByStudentIdAndCourseId(1L, 10L)).thenReturn(Optional.empty());
            when(enrollmentRepository.countByCourseIdAndStatusIn(eq(10L), anyCollection())).thenReturn(30L);

            assertThatThrownBy(() -> service.enroll(EMAIL, 10L))
                    .isInstanceOf(ConflictException.class).hasMessageContaining("full");
        }

        @Test
        void creditLimit_enforced() {
            when(enrollmentRepository.findByStudentIdAndCourseId(1L, 10L)).thenReturn(Optional.empty());
            givenSeatsTakenAndCredits(5, 21); // 21 + 4 > 24

            assertThatThrownBy(() -> service.enroll(EMAIL, 10L))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("25 credits");
        }

        @Test
        void exactlyAtCreditLimit_allowed() {
            when(enrollmentRepository.findByStudentIdAndCourseId(1L, 10L)).thenReturn(Optional.empty());
            givenSeatsTakenAndCredits(5, 20); // 20 + 4 = 24
            when(enrollmentRepository.save(any(Enrollment.class))).thenAnswer(inv -> inv.getArgument(0));

            assertThatCode(() -> service.enroll(EMAIL, 10L)).doesNotThrowAnyException();
        }

        @Test
        void reEnrollAfterDrop_reactivatesTheSameRow() {
            Enrollment dropped = enrollment(5, student, course, EnrollmentStatus.DROPPED, null);
            when(enrollmentRepository.findByStudentIdAndCourseId(1L, 10L)).thenReturn(Optional.of(dropped));
            givenSeatsTakenAndCredits(5, 0);
            when(enrollmentRepository.save(dropped)).thenReturn(dropped);

            MyEnrollment result = service.enroll(EMAIL, 10L);

            assertThat(result.id()).isEqualTo(5L);
            assertThat(dropped.getStatus()).isEqualTo(EnrollmentStatus.ENROLLED);
        }
    }

    @Nested
    @DisplayName("drop")
    class Drop {

        @Test
        void marksEnrollmentDropped() {
            Enrollment e = enrollment(5, student, course, EnrollmentStatus.ENROLLED, null);
            when(currentStudent.require(EMAIL)).thenReturn(student);
            when(enrollmentRepository.findByStudentIdAndCourseId(1L, 10L)).thenReturn(Optional.of(e));

            service.drop(EMAIL, 10L);

            assertThat(e.getStatus()).isEqualTo(EnrollmentStatus.DROPPED);
        }

        @Test
        void notEnrolled_notFound() {
            when(currentStudent.require(EMAIL)).thenReturn(student);
            when(enrollmentRepository.findByStudentIdAndCourseId(1L, 10L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.drop(EMAIL, 10L)).isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void completedCourse_cannotBeDropped() {
            when(currentStudent.require(EMAIL)).thenReturn(student);
            when(enrollmentRepository.findByStudentIdAndCourseId(1L, 10L))
                    .thenReturn(Optional.of(enrollment(5, student, course, EnrollmentStatus.COMPLETED, Grade.A)));

            assertThatThrownBy(() -> service.drop(EMAIL, 10L)).isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void afterEnrollmentClosed_rejected() {
            course.setEnrollmentOpen(false);
            when(currentStudent.require(EMAIL)).thenReturn(student);
            when(enrollmentRepository.findByStudentIdAndCourseId(1L, 10L))
                    .thenReturn(Optional.of(enrollment(5, student, course, EnrollmentStatus.ENROLLED, null)));

            assertThatThrownBy(() -> service.drop(EMAIL, 10L))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("closed");
        }
    }

    @Nested
    @DisplayName("grades and academic record")
    class Grades {

        @Test
        void assignGrade_completesEnrollment() {
            Enrollment e = enrollment(5, student, course, EnrollmentStatus.ENROLLED, null);
            when(enrollmentRepository.findById(5L)).thenReturn(Optional.of(e));

            var row = service.assignGrade(5L, Grade.A_PLUS);

            assertThat(e.getStatus()).isEqualTo(EnrollmentStatus.COMPLETED);
            assertThat(row.grade()).isEqualTo(Grade.A_PLUS);
        }

        @Test
        void droppedEnrollment_cannotBeGraded() {
            when(enrollmentRepository.findById(5L))
                    .thenReturn(Optional.of(enrollment(5, student, course, EnrollmentStatus.DROPPED, null)));

            assertThatThrownBy(() -> service.assignGrade(5L, Grade.A)).isInstanceOf(BadRequestException.class);
        }

        @Test
        void academicRecord_computesSgpaPerTermAndCgpa_newestTermFirst() {
            Course spring = course(1, "MA101", 4, 50, "Spring 2026", false);
            Course spring2 = course(2, "HS101", 2, 50, "Spring 2026", false);
            Course autumn = course(3, "CS201", 4, 50, "Autumn 2026", true);
            Course dropped = course(4, "CS299", 3, 50, "Autumn 2026", true);
            when(enrollmentRepository.findByStudentId(1L)).thenReturn(List.of(
                    enrollment(1, student, spring, EnrollmentStatus.COMPLETED, Grade.O),       // 10 × 4
                    enrollment(2, student, spring2, EnrollmentStatus.COMPLETED, Grade.B),      // 6 × 2
                    enrollment(3, student, autumn, EnrollmentStatus.ENROLLED, null),
                    enrollment(4, student, dropped, EnrollmentStatus.DROPPED, null)));
            when(attendanceRepository.summarize(anyCollection()))
                    .thenReturn(List.<Object[]>of(new Object[]{3L, 10L, 7L}));

            AcademicRecord record = service.academicRecord(1L);

            assertThat(record.cgpa()).isEqualTo(8.67); // 52 / 6
            assertThat(record.completedCredits()).isEqualTo(6);
            assertThat(record.terms()).extracting(t -> t.term()).containsExactly("Autumn 2026", "Spring 2026");
            assertThat(record.terms().get(0).sgpa()).isNull(); // nothing graded yet
            assertThat(record.terms().get(1).sgpa()).isEqualTo(8.67);
            assertThat(record.enrollments()).extracting(e -> e.course().code())
                    .containsExactly("CS201", "HS101", "MA101") // dropped course hidden
                    .doesNotContain("CS299");
            assertThat(record.enrollments().get(0).attendance().percentage()).isEqualTo(70.0);
            assertThat(record.enrollments().get(0).attendance().isBelowMinimum()).isTrue();
        }

        @Test
        void recordOf_unknownStudent_notFound() {
            when(studentRepository.existsById(42L)).thenReturn(false);

            assertThatThrownBy(() -> service.recordOf(42L)).isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Test
    void termSortKey_ordersByYearThenSeason() {
        assertThat(EnrollmentService.termSortKey("Autumn 2026")).isGreaterThan(EnrollmentService.termSortKey("Spring 2026"));
        assertThat(EnrollmentService.termSortKey("Spring 2027")).isGreaterThan(EnrollmentService.termSortKey("Autumn 2026"));
    }
}
