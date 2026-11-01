package com.student.studentservice.dto;

import com.student.studentservice.dto.CourseDtos.CourseSummary;
import com.student.studentservice.service.EnrollmentStatus;
import com.student.studentservice.service.Grade;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Request/response records for enrollments, grades and attendance. */
public final class EnrollmentDtos {

    private EnrollmentDtos() {
    }

    /** Attendance totals; percentage is null until at least one class has been recorded. */
    public record AttendanceSummary(long classesHeld, long classesAttended, Double percentage) {

        public static final double MINIMUM_PERCENTAGE = 75.0;

        public static AttendanceSummary of(long held, long attended) {
            Double pct = held == 0 ? null : Math.round(attended * 1000.0 / held) / 10.0;
            return new AttendanceSummary(held, attended, pct);
        }

        public static AttendanceSummary empty() {
            return new AttendanceSummary(0, 0, null);
        }

        public boolean isBelowMinimum() {
            return percentage != null && percentage < MINIMUM_PERCENTAGE;
        }
    }

    /** A student's own view of one of their courses. */
    public record MyEnrollment(
            Long id,
            CourseSummary course,
            EnrollmentStatus status,
            Grade grade,
            Integer gradePoints,
            LocalDateTime enrolledAt,
            AttendanceSummary attendance
    ) {
    }

    /** One row of a course roster (admin view). */
    public record RosterEntry(
            Long enrollmentId,
            Long studentId,
            String studentName,
            String studentEmail,
            String branch,
            EnrollmentStatus status,
            Grade grade,
            AttendanceSummary attendance
    ) {
    }

    public record GradeRequest(@NotNull(message = "Grade is required") Grade grade) {
    }

    public record AttendanceMark(@NotNull Long enrollmentId, boolean present) {
    }

    public record AttendanceRequest(
            @NotNull(message = "Class date is required")
            @PastOrPresent(message = "Attendance can't be recorded for a future date")
            LocalDate date,

            @NotNull @Valid List<AttendanceMark> records
    ) {
    }

    /** Attendance for one class date, plus the dates already recorded for the course. */
    public record AttendanceSheet(LocalDate date, List<AttendanceSheetRow> rows, List<LocalDate> recordedDates) {
    }

    /** present is null when attendance hasn't been taken for this student on that date yet. */
    public record AttendanceSheetRow(Long enrollmentId, Long studentId, String studentName, String branch,
                                     Boolean present) {
    }

    /** Academic record of one student: SGPA per term, CGPA and all enrollments. */
    public record AcademicRecord(Double cgpa, int completedCredits, List<TermResult> terms,
                                 List<MyEnrollment> enrollments) {
    }

    public record TermResult(String term, Double sgpa, int credits) {
    }
}
