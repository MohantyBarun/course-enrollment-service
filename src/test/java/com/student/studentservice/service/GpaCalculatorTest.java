package com.student.studentservice.service;

import com.student.studentservice.entity.Student;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.student.studentservice.service.AcademicTestData.*;
import static org.assertj.core.api.Assertions.assertThat;

class GpaCalculatorTest {

    private final Student s = student(1, "Asha Rao");

    @Test
    void creditWeightedAverage_onTenPointScale() {
        // (10×4 + 8×3 + 6×2) / 9 = 76 / 9 = 8.444 -> 8.44
        var enrollments = List.of(
                enrollment(1, s, course(1, "A", 4, 10, "Spring 2026", false), EnrollmentStatus.COMPLETED, Grade.O),
                enrollment(2, s, course(2, "B", 3, 10, "Spring 2026", false), EnrollmentStatus.COMPLETED, Grade.A),
                enrollment(3, s, course(3, "C", 2, 10, "Spring 2026", false), EnrollmentStatus.COMPLETED, Grade.B));

        assertThat(GpaCalculator.gpa(enrollments)).isEqualTo(8.44);
        assertThat(GpaCalculator.earnedCredits(enrollments)).isEqualTo(9);
    }

    @Test
    void failedCourses_countWithZeroPoints_butEarnNoCredits() {
        // (9×4 + 0×4) / 8 = 4.5
        var enrollments = List.of(
                enrollment(1, s, course(1, "A", 4, 10, "T", false), EnrollmentStatus.COMPLETED, Grade.A_PLUS),
                enrollment(2, s, course(2, "B", 4, 10, "T", false), EnrollmentStatus.COMPLETED, Grade.F));

        assertThat(GpaCalculator.gpa(enrollments)).isEqualTo(4.5);
        assertThat(GpaCalculator.earnedCredits(enrollments)).isEqualTo(4);
    }

    @Test
    void ungradedAndInProgressCourses_areIgnored() {
        var enrollments = List.of(
                enrollment(1, s, course(1, "A", 4, 10, "T", true), EnrollmentStatus.ENROLLED, null),
                enrollment(2, s, course(2, "B", 3, 10, "T", false), EnrollmentStatus.COMPLETED, Grade.C));

        assertThat(GpaCalculator.gpa(enrollments)).isEqualTo(5.0);
    }

    @Test
    void noGradedCourses_returnsNull() {
        assertThat(GpaCalculator.gpa(List.of())).isNull();
        assertThat(GpaCalculator.gpa(List.of(
                enrollment(1, s, course(1, "A", 4, 10, "T", true), EnrollmentStatus.ENROLLED, null)))).isNull();
    }

    @Test
    void gradeLabels_roundTrip() {
        assertThat(Grade.fromLabel("A+")).isEqualTo(Grade.A_PLUS);
        assertThat(Grade.fromLabel("a_plus")).isEqualTo(Grade.A_PLUS);
        assertThat(Grade.A_PLUS.getLabel()).isEqualTo("A+");
        assertThat(Grade.F.isPassing()).isFalse();
    }
}
