package com.seatsure.exception;

/** Thrown when credentials are wrong (HTTP 401). */
public class AuthenticationFailedException extends RuntimeException {
    public AuthenticationFailedException(String message) {
        super(message);
    }
}
