package com.student.studentservice.notification;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Sends through SMTP. The circuit breaker stops hammering a mail server that is down: while it's
 * open, calls fail immediately (CallNotPermittedException) and the outbox/queue retries later.
 */
@Slf4j
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final String from;
    private final CircuitBreaker circuitBreaker;

    public SmtpEmailSender(JavaMailSender mailSender, String from, CircuitBreaker circuitBreaker) {
        this.mailSender = mailSender;
        this.from = from;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public Delivery send(EmailMessage message) {
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(from);
        mail.setTo(message.to());
        mail.setSubject(message.subject());
        mail.setText(message.body());
        circuitBreaker.executeRunnable(() -> mailSender.send(mail));
        log.info("Email '{}' sent to {}", message.subject(), message.maskedRecipient());
        return Delivery.sent("Accepted by the SMTP server");
    }

    @Override
    public String mode() {
        return "SMTP";
    }
}
