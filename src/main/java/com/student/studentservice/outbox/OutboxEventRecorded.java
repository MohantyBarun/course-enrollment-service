package com.student.studentservice.outbox;

import java.util.UUID;

/** In-JVM signal that an outbox row was written; handled after the transaction commits. */
public record OutboxEventRecorded(UUID outboxEventId) {
}
