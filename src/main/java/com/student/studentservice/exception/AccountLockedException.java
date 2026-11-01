package com.student.studentservice.exception;

/** Thrown when the account exists but may not log in: blocked, deactivated, or temp password expired/exhausted (HTTP 403). */
public class AccountLockedException extends RuntimeException {
    public AccountLockedException(String message) {
        super(message);
    }
}
