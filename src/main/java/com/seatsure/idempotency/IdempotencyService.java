package com.seatsure.idempotency;

import java.time.Clock;
import com.seatsure.entity.IdempotencyRecord;
import com.seatsure.repository.IdempotencyRecordRepository;
import com.seatsure.util.SecretBox;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Claims, completes and replays Idempotency-Key records. Each step commits on its own, outside the
 * business transaction: the claim must be visible to concurrent retries before the work starts.
 */
@Slf4j
@Service
public class IdempotencyService {

    /** Outcome of trying to claim a key. */
    public sealed interface Claim {
        record Acquired(Long recordId) implements Claim { }
        record Replay(int status, String body, String contentType, String location) implements Claim { }
        record InProgress() implements Claim { }
        record Mismatch() implements Claim { }
    }

    private final IdempotencyRecordRepository repository;
    private final SecretBox secretBox;
    private final TransactionTemplate tx;
    private final Duration ttl;
    private final Duration inProgressTimeout;
    private final Clock clock;

    public IdempotencyService(IdempotencyRecordRepository repository, SecretBox secretBox,
                              PlatformTransactionManager transactionManager,
                              @Value("${app.idempotency.ttl:PT24H}") Duration ttl,
                              @Value("${app.idempotency.in-progress-timeout:PT2M}") Duration inProgressTimeout,
                              Clock clock) {
        this.repository = repository;
        this.secretBox = secretBox;
        this.tx = new TransactionTemplate(transactionManager);
        this.ttl = ttl;
        this.inProgressTimeout = inProgressTimeout;
        this.clock = clock;
    }

    public Claim claim(String owner, String key, String requestHash) {
        try {
            return tx.execute(status -> claimInTransaction(owner, key, requestHash));
        } catch (DataIntegrityViolationException e) {
            // Lost the race: an identical request inserted the key a moment ago and is still running
            return new Claim.InProgress();
        }
    }

    private Claim claimInTransaction(String owner, String key, String requestHash) {
        Instant now = clock.instant();
        Optional<IdempotencyRecord> existing = repository.findByOwnerAndIdempotencyKey(owner, key);
        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();
            if (record.getExpiresAt().isAfter(now)) {
                if (!record.getRequestHash().equals(requestHash)) {
                    return new Claim.Mismatch();
                }
                if (record.getStatus() == IdempotencyRecord.Status.COMPLETED) {
                    return new Claim.Replay(record.getResponseStatus(),
                            record.getResponseBody() == null ? null : secretBox.decrypt(record.getResponseBody()),
                            record.getResponseContentType(), record.getResponseLocation());
                }
                if (!isAbandoned(record, now)) {
                    return new Claim.InProgress();
                }
                // The instance running it died between committing the work and storing the response
                // (a deploy or crash). Run it again rather than answering 409 for the next 24 hours.
                log.warn("Idempotency key {} was in progress since {}; reclaiming it", record.getId(), record.getCreatedAt());
            }
            if (repository.deleteRecord(record.getId()) == 0) {
                return new Claim.InProgress(); // another retry reclaimed it a moment ago
            }
        }
        IdempotencyRecord record = IdempotencyRecord.inProgress(owner, key, requestHash, now, now.plus(ttl));
        return new Claim.Acquired(repository.saveAndFlush(record).getId());
    }

    /**
     * An in-progress key older than any request takes. Requests here finish in seconds, so a few
     * minutes means the instance running it is gone. Redoing it is safe for every endpoint behind
     * this filter: each re-checks its own state (a hold that's already paid, a booking that's
     * already cancelled) and answers accordingly.
     */
    private boolean isAbandoned(IdempotencyRecord record, Instant now) {
        return record.getCreatedAt().plus(inProgressTimeout).isBefore(now);
    }

    public void complete(Long recordId, int status, String body, String contentType, String location) {
        tx.executeWithoutResult(s -> repository.findById(recordId).ifPresent(record -> record.complete(status,
                body == null || body.isEmpty() ? null : secretBox.encrypt(body), contentType, location)));
    }

    /** Frees the key after a server error, so the client's retry runs the request again. */
    public void release(Long recordId) {
        tx.executeWithoutResult(s -> repository.deleteById(recordId));
    }

    @Scheduled(cron = "0 25 3 * * *", zone = "Asia/Kolkata")
    public void purgeExpired() {
        Integer deleted = tx.execute(s -> repository.deleteExpired(clock.instant()));
        log.info("Purged {} expired idempotency keys", deleted);
    }
}
