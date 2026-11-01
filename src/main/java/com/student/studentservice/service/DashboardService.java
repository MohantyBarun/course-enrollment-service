package com.student.studentservice.service;

import com.student.studentservice.config.CacheConfig;
import com.student.studentservice.dto.CourseDtos.CourseResponse;
import com.student.studentservice.dto.DashboardDtos.*;
import com.student.studentservice.dto.EnrollmentDtos.AcademicRecord;
import com.student.studentservice.dto.EnrollmentDtos.AttendanceSummary;
import com.student.studentservice.dto.EnrollmentDtos.MyEnrollment;
import com.student.studentservice.entity.Enrollment;
import com.student.studentservice.entity.Student;
import com.student.studentservice.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final int LOW_ATTENDANCE_LIMIT = 8;
    private static final int COURSE_FILL_LIMIT = 6;
    /** Ignore attendance warnings until a few classes have been held. */
    private static final int MIN_CLASSES_FOR_WARNING = 3;

    private final StudentRepository studentRepository;
    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final AttendanceRecordRepository attendanceRepository;
    private final CourseService courseService;
    private final EnrollmentService enrollmentService;
    private final AnnouncementService announcementService;
    private final CurrentStudentResolver currentStudent;

    /** The same for every admin (email only feeds the unfiltered admin notice list); ~1 min TTL. */
    @Cacheable(cacheNames = CacheConfig.ADMIN_DASHBOARD, key = "'all'")
    @Transactional(readOnly = true)
    public AdminDashboard admin(String email) {
        List<Enrollment> active = enrollmentRepository.findByStatus(EnrollmentStatus.ENROLLED);
        Map<Long, AttendanceSummary> attendance = enrollmentService.summaries(active);

        List<LowAttendanceEntry> low = active.stream()
                .filter(e -> {
                    AttendanceSummary s = attendance.get(e.getId());
                    return s != null && s.classesHeld() >= MIN_CLASSES_FOR_WARNING && s.isBelowMinimum();
                })
                .map(e -> new LowAttendanceEntry(e.getStudent().getId(), e.getStudent().getPersons().getName(),
                        e.getCourse().getId(), e.getCourse().getCode(), attendance.get(e.getId()).percentage()))
                .sorted(Comparator.comparingDouble(LowAttendanceEntry::percentage))
                .limit(LOW_ATTENDANCE_LIMIT)
                .toList();

        List<CourseFill> fill = courseService.list(null, null, null).stream()
                .filter(CourseResponse::enrollmentOpen)
                .sorted(Comparator.comparingDouble((CourseResponse c) -> (double) c.enrolledCount() / c.capacity()).reversed())
                .limit(COURSE_FILL_LIMIT)
                .map(c -> new CourseFill(c.id(), c.code(), c.title(), c.enrolledCount(), c.capacity()))
                .toList();

        List<DepartmentCount> departments = studentRepository.countByDepartment().stream()
                .map(r -> new DepartmentCount((String) r[0], ((Number) r[1]).longValue()))
                .toList();

        return new AdminDashboard(studentRepository.count(), courseRepository.count(), active.size(),
                overallAttendance(), low, fill, departments, announcementService.feed(email, true, 3));
    }

    @Transactional(readOnly = true)
    public StudentDashboard student(String email) {
        Student student = currentStudent.require(email);
        AcademicRecord record = enrollmentService.academicRecord(student.getId());

        List<MyEnrollment> current = record.enrollments().stream()
                .filter(e -> e.status() == EnrollmentStatus.ENROLLED)
                .toList();
        long held = current.stream().mapToLong(e -> e.attendance().classesHeld()).sum();
        long attended = current.stream().mapToLong(e -> e.attendance().classesAttended()).sum();
        long below = current.stream()
                .filter(e -> e.attendance().classesHeld() >= MIN_CLASSES_FOR_WARNING && e.attendance().isBelowMinimum())
                .count();

        return new StudentDashboard(student.getPersons().getName(), student.getDepartment(), student.getBranch(),
                record.cgpa(), record.completedCredits(), current.stream().mapToInt(e -> e.course().credits()).sum(),
                AttendanceSummary.of(held, attended).percentage(), below, current,
                announcementService.feed(email, false, 5));
    }

    private Double overallAttendance() {
        List<Object[]> rows = attendanceRepository.totals();
        if (rows.isEmpty() || rows.get(0)[0] == null) {
            return null;
        }
        long held = ((Number) rows.get(0)[0]).longValue();
        long attended = rows.get(0)[1] == null ? 0 : ((Number) rows.get(0)[1]).longValue();
        return AttendanceSummary.of(held, attended).percentage();
    }
}
