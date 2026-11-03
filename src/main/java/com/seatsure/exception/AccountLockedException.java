package com.seatsure.exception;

/** Thrown when the account exists but may not log in: blocked after wrong passwords, blocked by support, or deactivated (HTTP 403). */
public class AccountLockedException extends RuntimeException {
    public AccountLockedException(String message) {
        super(message);
    }
}
