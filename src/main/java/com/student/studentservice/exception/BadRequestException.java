package com.student.studentservice.exception;

/** Thrown for requests that pass bean validation but are logically invalid (HTTP 400). */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
