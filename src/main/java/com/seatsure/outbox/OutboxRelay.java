package com.seatsure.outbox;

import com.seatsure.entity.OutboxEvent;
import com.seatsure.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Moves outbox events to the transport. Runs right after a business transaction commits and, as a
 * safety net, on a schedule (picks up retries and anything missed, e.g. after a crash or restart).
 */
@Slf4j
@Component
public class OutboxRelay {

    private final OutboxEventRepository outboxRepository;
    private final OutboxTransport transport;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration retention;
    private final Counter published;
    private final Counter retried;
    private final Counter deadLettered;

    public OutboxRelay(OutboxEventRepository outboxRepository,
                       OutboxTransport transport,
                       PlatformTransactionManager transactionManager,
                       MeterRegistry meterRegistry,
                       @Value("${app.outbox.batch-size:50}") int batchSize,
                       @Value("${app.outbox.max-attempts:10}") int maxAttempts,
                       @Value("${app.outbox.retention:P7D}") Duration retention) {
        this.outboxRepository = outboxRepository;
        this.transport = transport;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.retention = retention;
        this.published = Counter.builder("outbox.events.relayed").tag("outcome", "published").register(meterRegistry);
        this.retried = Counter.builder("outbox.events.relayed").tag("outcome", "retry").register(meterRegistry);
        this.deadLettered = Counter.builder("outbox.events.relayed").tag("outcome", "failed").register(meterRegistry);
        Gauge.builder("outbox.events.pending", outboxRepository, r -> r.countByStatus(OutboxEvent.Status.PENDING))
                .description("Outbox events waiting to be delivered").register(meterRegistry);
        Gauge.builder("outbox.events.failed", outboxRepository, r -> r.countByStatus(OutboxEvent.Status.FAILED))
                .description("Outbox events that exhausted their retries").register(meterRegistry);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEventRecorded(OutboxEventRecorded recorded) {
        relayPending();
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval:PT30S}", initialDelayString = "PT15S")
    public void poll() {
        relayPending();
    }

    /** Relays due events batch by batch until none are left; returns how many were attempted. */
    public int relayPending() {
        int total = 0;
        int attempted;
        do {
            Integer n = transactionTemplate.execute(status -> relayBatch());
            attempted = n == null ? 0 : n;
            total += attempted;
        } while (attempted == batchSize);
        return total;
    }

    private int relayBatch() {
        List<OutboxEvent> batch = outboxRepository.lockNextBatch(LocalDateTime.now(), batchSize);
        for (OutboxEvent event : batch) {
            try {
                transport.send(new OutboxEvents.Message(event.getId(), event.getEventType(), event.getPayload()));
                event.markPublished(LocalDateTime.now());
                published.increment();
            } catch (Exception e) {
                event.markFailed(e.toString(), LocalDateTime.now(), maxAttempts);
                if (event.getStatus() == OutboxEvent.Status.FAILED) {
                    deadLettered.increment();
                    log.error("Outbox event {} ({}) failed permanently after {} attempts: {}",
                            event.getId(), event.getEventType(), event.getAttempts(), e.toString());
                } else {
                    retried.increment();
                    log.warn("Outbox event {} ({}) failed, attempt {}; retrying at {}: {}",
                            event.getId(), event.getEventType(), event.getAttempts(), event.getNextAttemptAt(),
                            e.toString());
                }
            }
        }
        return batch.size();
    }

    @Scheduled(cron = "0 15 3 * * *", zone = "Asia/Kolkata")
    public void purgePublished() {
        Integer deleted = transactionTemplate.execute(status -> outboxRepository.deleteByStatusAndPublishedAtBefore(
                OutboxEvent.Status.PUBLISHED, LocalDateTime.now().minus(retention)));
        log.info("Purged {} published outbox events older than {}", deleted, retention);
    }
}
