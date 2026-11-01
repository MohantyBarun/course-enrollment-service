package com.student.studentservice.service;

import com.student.studentservice.config.CacheConfig;
import com.student.studentservice.dto.EnrollmentDtos.*;
import com.student.studentservice.entity.Course;
import com.student.studentservice.entity.Enrollment;
import com.student.studentservice.entity.Student;
import com.student.studentservice.exception.BadRequestException;
import com.student.studentservice.exception.ConflictException;
import com.student.studentservice.exception.ResourceNotFoundException;
import com.student.studentservice.repository.AttendanceRecordRepository;
import com.student.studentservice.repository.EnrollmentRepository;
import com.student.studentservice.outbox.OutboxEvents;
import com.student.studentservice.outbox.OutboxService;
import com.student.studentservice.repository.StudentRepository;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EnrollmentService {

    /** Maximum credits a student may take in one term. */
    public static final int MAX_CREDITS_PER_TERM = 24;

    private final EnrollmentRepository enrollmentRepository;
    private final AttendanceRecordRepository attendanceRepository;
    private final StudentRepository studentRepository;
    private final CourseService courseService;
    private final CurrentStudentResolver currentStudent;
    private final OutboxService outbox;

    // ------------------------------------------------------------------ student actions

    /**
     * Check-then-insert is only safe if nobody can change the inputs in between, so the student row
     * and then the course row are locked (always in that order, so two enrollments can't deadlock).
     * Two students racing for the last seat: the second waits for the first to commit, then sees the
     * course full. One student enrolling in two courses at once: the second sees the first's credits.
     */
    @Observed(name = "enrollment.enroll", contextualName = "enroll")
    @CacheEvict(cacheNames = CacheConfig.COURSE_CATALOG, allEntries = true)
    @Transactional
    public MyEnrollment enroll(String email, Long courseId) {
        Long studentId = currentStudent.require(email).getId();
        Student student = studentRepository.findByIdForUpdate(studentId)
                .orElseThrow(() -> new ResourceNotFoundException("No student profile found for: " + email));
        Course course = courseService.findForUpdate(courseId);

        if (!Boolean.TRUE.equals(course.getEnrollmentOpen())) {
            throw new BadRequestException("Enrollment for " + course.getCode() + " is closed");
        }

        Optional<Enrollment> existing = enrollmentRepository.findByStudentIdAndCourseId(student.getId(), courseId);
        if (existing.isPresent() && existing.get().getStatus() == EnrollmentStatus.ENROLLED) {
            throw new ConflictException("You're already enrolled in " + course.getCode());
        }
        if (existing.isPresent() && existing.get().getStatus() == EnrollmentStatus.COMPLETED) {
            throw new ConflictException("You've already completed " + course.getCode());
        }

        long taken = enrollmentRepository.countByCourseIdAndStatusIn(courseId, CourseService.SEAT_HOLDING);
        if (taken >= course.getCapacity()) {
            throw new ConflictException(course.getCode() + " is full");
        }

        long credits = enrollmentRepository.sumCredits(student.getId(), course.getTerm(), EnrollmentStatus.ENROLLED);
        if (credits + course.getCredits() > MAX_CREDITS_PER_TERM) {
            throw new BadRequestException("Enrolling would take you to " + (credits + course.getCredits())
                    + " credits this term; the limit is " + MAX_CREDITS_PER_TERM);
        }

        // Re-enrolling after a drop reactivates the original row (keeping its attendance history)
        Enrollment enrollment = existing.orElseGet(Enrollment::new);
        enrollment.setStudent(student);
        enrollment.setCourse(course);
        enrollment.setStatus(EnrollmentStatus.ENROLLED);
        enrollment.setGrade(null);
        Enrollment saved = enrollmentRepository.save(enrollment);
        log.info("Student {} enrolled in {}", student.getId(), course.getCode());
        outbox.record("Enrollment", saved.getId(), OutboxEvents.ENROLLMENT_CONFIRMED,
                new OutboxEvents.EnrollmentConfirmed(saved.getId(), student.getId(), student.getPersons().getName(),
                        student.getPersons().getEmail(), course.getCode(), course.getTitle(), course.getTerm(),
                        course.getCredits()));
        return toMyEnrollment(saved, summaries(List.of(saved)));
    }

    @CacheEvict(cacheNames = CacheConfig.COURSE_CATALOG, allEntries = true)
    @Transactional
    public void drop(String email, Long courseId) {
        Student student = currentStudent.require(email);
        Enrollment enrollment = enrollmentRepository.findByStudentIdAndCourseId(student.getId(), courseId)
                .filter(e -> e.getStatus() == EnrollmentStatus.ENROLLED)
                .orElseThrow(() -> new ResourceNotFoundException("You're not enrolled in this course"));
        if (!Boolean.TRUE.equals(enrollment.getCourse().getEnrollmentOpen())) {
            throw new BadRequestException("Enrollment for " + enrollment.getCourse().getCode()
                    + " is closed, so it can't be dropped. Contact the academic office.");
        }
        enrollment.setStatus(EnrollmentStatus.DROPPED);
        log.info("Student {} dropped {}", student.getId(), enrollment.getCourse().getCode());
    }

    @Transactional(readOnly = true)
    public AcademicRecord myRecord(String email) {
        return academicRecord(currentStudent.require(email).getId());
    }

    // ------------------------------------------------------------------ admin actions

    @Transactional(readOnly = true)
    public AcademicRecord recordOf(Long studentId) {
        if (!studentRepository.existsById(studentId)) {
            throw new ResourceNotFoundException("Student not found with id: " + studentId);
        }
        return academicRecord(studentId);
    }

    @Transactional(readOnly = true)
    public List<RosterEntry> roster(Long courseId) {
        courseService.find(courseId);
        List<Enrollment> enrollments = enrollmentRepository.findByCourseIdAndStatusIn(courseId, CourseService.SEAT_HOLDING);
        Map<Long, AttendanceSummary> attendance = summaries(enrollments);
        return enrollments.stream()
                .map(e -> new RosterEntry(e.getId(), e.getStudent().getId(), e.getStudent().getPersons().getName(),
                        e.getStudent().getPersons().getEmail(), e.getStudent().getBranch(), e.getStatus(), e.getGrade(),
                        attendance.getOrDefault(e.getId(), AttendanceSummary.empty())))
                .sorted(Comparator.comparing(RosterEntry::studentName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Transactional
    public RosterEntry assignGrade(Long enrollmentId, Grade grade) {
        Enrollment e = enrollmentRepository.findById(enrollmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Enrollment not found with id: " + enrollmentId));
        if (e.getStatus() == EnrollmentStatus.DROPPED) {
            throw new BadRequestException("Can't grade a dropped enrollment");
        }
        e.setGrade(grade);
        e.setStatus(EnrollmentStatus.COMPLETED);
        log.info("Graded enrollment {} as {}", enrollmentId, grade.getLabel());
        return new RosterEntry(e.getId(), e.getStudent().getId(), e.getStudent().getPersons().getName(),
                e.getStudent().getPersons().getEmail(), e.getStudent().getBranch(), e.getStatus(), e.getGrade(),
                summaries(List.of(e)).getOrDefault(e.getId(), AttendanceSummary.empty()));
    }

    // ------------------------------------------------------------------ shared

    AcademicRecord academicRecord(Long studentId) {
        List<Enrollment> enrollments = enrollmentRepository.findByStudentId(studentId).stream()
                .filter(e -> e.getStatus() != EnrollmentStatus.DROPPED)
                .toList();
        Map<Long, AttendanceSummary> attendance = summaries(enrollments);

        // SGPA per term, newest term first (terms are "Season Year"; sort by year, then Autumn after Spring)
        List<TermResult> terms = enrollments.stream()
                .collect(Collectors.groupingBy(e -> e.getCourse().getTerm(), TreeMap::new, Collectors.toList()))
                .entrySet().stream()
                .map(t -> new TermResult(t.getKey(), GpaCalculator.gpa(t.getValue()), t.getValue().stream()
                        .mapToInt(e -> e.getCourse().getCredits()).sum()))
                .sorted(Comparator.comparing((TermResult t) -> termSortKey(t.term())).reversed())
                .toList();

        List<MyEnrollment> views = enrollments.stream()
                .sorted(Comparator.comparing((Enrollment e) -> termSortKey(e.getCourse().getTerm())).reversed()
                        .thenComparing(e -> e.getCourse().getCode()))
                .map(e -> toMyEnrollment(e, attendance))
                .toList();

        return new AcademicRecord(GpaCalculator.gpa(enrollments), GpaCalculator.earnedCredits(enrollments), terms, views);
    }

    /** Attendance summary per enrollment id (one aggregate query). */
    Map<Long, AttendanceSummary> summaries(Collection<Enrollment> enrollments) {
        if (enrollments.isEmpty()) {
            return Map.of();
        }
        Map<Long, AttendanceSummary> result = new HashMap<>();
        List<Long> ids = enrollments.stream().map(Enrollment::getId).filter(Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return result;
        }
        for (Object[] row : attendanceRepository.summarize(ids)) {
            result.put((Long) row[0], AttendanceSummary.of(((Number) row[1]).longValue(), ((Number) row[2]).longValue()));
        }
        return result;
    }

    MyEnrollment toMyEnrollment(Enrollment e, Map<Long, AttendanceSummary> attendance) {
        return new MyEnrollment(e.getId(), CourseService.toSummary(e.getCourse()), e.getStatus(), e.getGrade(),
                e.getGrade() == null ? null : e.getGrade().getPoints(), e.getEnrolledAt(),
                attendance.getOrDefault(e.getId(), AttendanceSummary.empty()));
    }

    /** "Autumn 2026" -> 20262, "Spring 2026" -> 20261; unknown formats sort by their text. */
    static String termSortKey(String term) {
        String[] parts = term.trim().split("\\s+");
        if (parts.length == 2 && parts[1].matches("\\d{4}")) {
            String season = parts[0].toLowerCase();
            int order = switch (season) {
                case "spring", "winter", "even" -> 1;
                case "summer" -> 2;
                case "autumn", "fall", "monsoon", "odd" -> 3;
                default -> 0;
            };
            return parts[1] + order;
        }
        return term;
    }
}
