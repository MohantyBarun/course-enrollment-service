package com.student.studentservice.notification;

import lombok.extern.slf4j.Slf4j;

/** Used when SMTP isn't configured, and on a demo without an allowlist. Never logs the body (it may hold a password). */
@Slf4j
public class LoggingEmailSender implements EmailSender {

    private final String reason;

    public LoggingEmailSender(String reason) {
        this.reason = reason;
    }

    @Override
    public Delivery send(EmailMessage message) {
        log.info("Email '{}' to {} not sent ({})", message.subject(), message.maskedRecipient(), reason);
        return Delivery.skipped("Not sent: " + reason);
    }

    @Override
    public String mode() {
        return "Logged only (" + reason + ")";
    }
}
