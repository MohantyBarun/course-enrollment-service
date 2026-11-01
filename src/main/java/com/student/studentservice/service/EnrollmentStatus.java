package com.student.studentservice.service;

public enum EnrollmentStatus {
    /** Currently taking the course. */
    ENROLLED,
    /** Left the course before it was graded. Keeps the row (and its attendance) for history. */
    DROPPED,
    /** Graded; counts toward CGPA. */
    COMPLETED;

    /** Statuses that occupy a seat in the course. */
    public boolean holdsSeat() {
        return this != DROPPED;
    }
}
