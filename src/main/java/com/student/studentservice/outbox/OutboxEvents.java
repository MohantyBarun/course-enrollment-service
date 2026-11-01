package com.student.studentservice.outbox;

import java.util.UUID;

/** Event types (also the RabbitMQ routing keys) and their payloads. */
public final class OutboxEvents {

    public static final String STUDENT_REGISTERED = "student.registered";
    public static final String ENROLLMENT_CONFIRMED = "enrollment.confirmed";
    public static final String TEST_EMAIL = "notification.test";

    private OutboxEvents() {
    }

    /** The temporary password is AES-GCM encrypted (SecretBox); it's never stored or queued in plain text. */
    public record StudentRegistered(Long studentId, String name, String email, String encryptedTempPassword,
                                    int tempPasswordValidHours) {
    }

    public record EnrollmentConfirmed(Long enrollmentId, Long studentId, String name, String email,
                                      String courseCode, String courseTitle, String term, Integer credits) {
    }

    /** An admin checking the email setup end to end (SMTP credentials, sender, allowlist). */
    public record TestEmailRequested(String email, String requestedBy) {
    }

    /** What a transport carries: the outbox row id doubles as the message id for consumer de-duplication. */
    public record Message(UUID id, String type, String payload) {
    }
}
