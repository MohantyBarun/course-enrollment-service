package com.student.studentservice.service;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 10-point letter grades as used by Indian universities (UGC CBCS scheme).
 * CGPA = sum(gradePoints × credits) / sum(credits) over completed courses.
 */
public enum Grade {
    O("O", 10, "Outstanding"),
    A_PLUS("A+", 9, "Excellent"),
    A("A", 8, "Very good"),
    B_PLUS("B+", 7, "Good"),
    B("B", 6, "Above average"),
    C("C", 5, "Average"),
    P("P", 4, "Pass"),
    F("F", 0, "Fail");

    private final String label;
    private final int points;
    private final String description;

    Grade(String label, int points, String description) {
        this.label = label;
        this.points = points;
        this.description = description;
    }

    /** Serialised as the familiar label ("A+") rather than the enum name ("A_PLUS"). */
    @JsonValue
    public String getLabel() {
        return label;
    }

    public int getPoints() {
        return points;
    }

    public String getDescription() {
        return description;
    }

    public boolean isPassing() {
        return this != F;
    }

    /** Accepts either the label ("A+") or the enum name ("A_PLUS"). */
    @com.fasterxml.jackson.annotation.JsonCreator
    public static Grade fromLabel(String value) {
        for (Grade grade : values()) {
            if (grade.label.equalsIgnoreCase(value) || grade.name().equalsIgnoreCase(value)) {
                return grade;
            }
        }
        throw new IllegalArgumentException("Unknown grade: " + value);
    }
}
