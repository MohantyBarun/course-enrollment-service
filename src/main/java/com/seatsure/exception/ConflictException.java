package com.seatsure.exception;

/** Thrown when a request clashes with the current state, e.g. an email that is already registered (HTTP 409). */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
