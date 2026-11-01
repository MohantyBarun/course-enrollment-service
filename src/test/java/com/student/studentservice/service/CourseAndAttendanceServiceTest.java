package com.student.studentservice.service;

import com.student.studentservice.dto.CourseDtos.CourseRequest;
import com.student.studentservice.dto.EnrollmentDtos.AttendanceMark;
import com.student.studentservice.dto.EnrollmentDtos.AttendanceRequest;
import com.student.studentservice.entity.AttendanceRecord;
import com.student.studentservice.entity.Course;
import com.student.studentservice.entity.Enrollment;
import com.student.studentservice.entity.Student;
import com.student.studentservice.exception.BadRequestException;
import com.student.studentservice.exception.ConflictException;
import com.student.studentservice.repository.AttendanceRecordRepository;
import com.student.studentservice.repository.CourseRepository;
import com.student.studentservice.repository.EnrollmentRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.student.studentservice.service.AcademicTestData.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CourseAndAttendanceServiceTest {

    @Mock private CourseRepository courseRepository;
    @Mock private EnrollmentRepository enrollmentRepository;
    @Mock private AttendanceRecordRepository attendanceRepository;
    @Mock private CourseService courseServiceMock;

    @InjectMocks private CourseService courseService;

    private static CourseRequest request(String code, int capacity) {
        return new CourseRequest(code, "Data Structures", null, 4, "Computer Science", "Autumn 2026", capacity,
                " Dr. Iyer ", "", null);
    }

    @Nested
    class Courses {

        @Test
        void create_normalisesFields() {
            when(courseRepository.existsByCodeIgnoreCase("cs201")).thenReturn(false);
            when(courseRepository.save(any(Course.class))).thenAnswer(inv -> {
                Course c = inv.getArgument(0);
                c.setId(1L);
                return c;
            });

            var response = courseService.create(request("cs201", 40));

            assertThat(response.code()).isEqualTo("CS201");
            assertThat(response.instructor()).isEqualTo("Dr. Iyer");
            assertThat(response.schedule()).isNull();
            assertThat(response.enrollmentOpen()).isTrue();
            assertThat(response.seatsLeft()).isEqualTo(40);
        }

        @Test
        void create_duplicateCode_conflict() {
            when(courseRepository.existsByCodeIgnoreCase("CS201")).thenReturn(true);

            assertThatThrownBy(() -> courseService.create(request("CS201", 40))).isInstanceOf(ConflictException.class);
        }

        @Test
        void update_capacityBelowEnrolled_rejected() {
            when(courseRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(course(1, "CS201", 4, 40, "Autumn 2026", true)));
            when(enrollmentRepository.countByCourseIdAndStatusIn(eq(1L), anyCollection())).thenReturn(25L);

            assertThatThrownBy(() -> courseService.update(1L, request("CS201", 20)))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("25");
        }

        @Test
        void delete_withEnrollments_conflict() {
            when(courseRepository.findById(1L)).thenReturn(Optional.of(course(1, "CS201", 4, 40, "Autumn 2026", true)));
            when(enrollmentRepository.existsByCourseId(1L)).thenReturn(true);

            assertThatThrownBy(() -> courseService.delete(1L)).isInstanceOf(ConflictException.class);
            verify(courseRepository, never()).delete(any(Course.class));
        }

        @Test
        void list_reportsSeatsTaken() {
            Course c = course(1, "CS201", 4, 40, "Autumn 2026", true);
            when(enrollmentRepository.countByCourseGrouped(anyCollection()))
                    .thenReturn(List.<Object[]>of(new Object[]{1L, 38L}));
            when(courseRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                    any(org.springframework.data.domain.Sort.class))).thenReturn(List.of(c));

            var list = courseService.list(null, null, null);

            assertThat(list.get(0).enrolledCount()).isEqualTo(38);
            assertThat(list.get(0).seatsLeft()).isEqualTo(2);
        }
    }

    @Nested
    class Attendance {

        private final Student asha = student(1, "Asha Rao");
        private final Student bala = student(2, "Bala Das");
        private final Course cs201 = course(10, "CS201", 4, 40, "Autumn 2026", true);
        private final Enrollment e1 = enrollment(100, asha, cs201, EnrollmentStatus.ENROLLED, null);
        private final Enrollment e2 = enrollment(200, bala, cs201, EnrollmentStatus.ENROLLED, null);
        private final LocalDate date = LocalDate.of(2026, 10, 20);

        private AttendanceService attendanceService() {
            return new AttendanceService(attendanceRepository, enrollmentRepository, courseServiceMock);
        }

        @Test
        void record_createsNewRowsAndUpdatesExistingOnes() {
            AttendanceRecord existing = new AttendanceRecord(e1, date, false);
            when(enrollmentRepository.findByCourseIdAndStatusIn(eq(10L), anyCollection())).thenReturn(List.of(e1, e2));
            when(attendanceRepository.findByEnrollmentCourseIdAndClassDate(10L, date)).thenReturn(List.of(existing));
            when(attendanceRepository.findClassDates(10L)).thenReturn(List.of(date));

            attendanceService().record(10L, new AttendanceRequest(date,
                    List.of(new AttendanceMark(100L, true), new AttendanceMark(200L, false))));

            assertThat(existing.getPresent()).isTrue(); // updated in place, no duplicate row
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<AttendanceRecord>> saved = ArgumentCaptor.forClass(List.class);
            verify(attendanceRepository).saveAll(saved.capture());
            assertThat(saved.getValue()).singleElement().satisfies(r -> {
                assertThat(r.getEnrollment()).isSameAs(e2);
                assertThat(r.getPresent()).isFalse();
            });
        }

        @Test
        void record_rejectsStudentsNotOnTheRoster() {
            when(enrollmentRepository.findByCourseIdAndStatusIn(eq(10L), anyCollection())).thenReturn(List.of(e1));
            when(attendanceRepository.findByEnrollmentCourseIdAndClassDate(10L, date)).thenReturn(List.of());

            assertThatThrownBy(() -> attendanceService().record(10L,
                    new AttendanceRequest(date, List.of(new AttendanceMark(999L, true)))))
                    .isInstanceOf(BadRequestException.class);
            verify(attendanceRepository, never()).saveAll(any());
        }

        @Test
        void sheet_marksUntakenAttendanceAsNull() {
            when(enrollmentRepository.findByCourseIdAndStatusIn(eq(10L), anyCollection())).thenReturn(List.of(e2, e1));
            when(attendanceRepository.findByEnrollmentCourseIdAndClassDate(10L, date))
                    .thenReturn(List.of(new AttendanceRecord(e1, date, true)));
            when(attendanceRepository.findClassDates(10L)).thenReturn(List.of(date));

            var sheet = attendanceService().sheet(10L, date);

            assertThat(sheet.rows()).extracting(r -> r.studentName()).containsExactly("Asha Rao", "Bala Das");
            assertThat(sheet.rows()).extracting(r -> r.present()).containsExactly(true, null);
        }
    }
}
