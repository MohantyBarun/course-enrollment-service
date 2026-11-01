package com.student.studentservice.service;

import com.student.studentservice.entity.Enrollment;

import java.util.Collection;

/**
 * Credit-weighted grade point averages on the 10-point scale.
 * SGPA/CGPA = Σ(grade points × credits) / Σ(credits), over graded (COMPLETED) courses, rounded to 2 decimals.
 * Failed courses count with 0 points, as in the UGC CBCS scheme.
 */
public final class GpaCalculator {

    private GpaCalculator() {
    }

    /** Returns null when nothing has been graded yet. */
    public static Double gpa(Collection<Enrollment> enrollments) {
        int weightedPoints = 0;
        int credits = 0;
        for (Enrollment e : enrollments) {
            if (isGraded(e)) {
                weightedPoints += e.getGrade().getPoints() * e.getCourse().getCredits();
                credits += e.getCourse().getCredits();
            }
        }
        return credits == 0 ? null : Math.round(weightedPoints * 100.0 / credits) / 100.0;
    }

    /** Credits earned: graded courses with a passing grade. */
    public static int earnedCredits(Collection<Enrollment> enrollments) {
        return enrollments.stream()
                .filter(e -> isGraded(e) && e.getGrade().isPassing())
                .mapToInt(e -> e.getCourse().getCredits())
                .sum();
    }

    private static boolean isGraded(Enrollment e) {
        return e.getStatus() == EnrollmentStatus.COMPLETED && e.getGrade() != null;
    }
}
