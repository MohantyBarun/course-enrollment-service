package com.student.studentservice.exception;

/** Thrown when a request clashes with the current state, e.g. a full course or a duplicate enrollment (HTTP 409). */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
