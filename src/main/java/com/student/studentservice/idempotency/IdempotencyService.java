package com.student.studentservice.idempotency;

import com.student.studentservice.entity.IdempotencyRecord;
import com.student.studentservice.repository.IdempotencyRecordRepository;
import com.student.studentservice.util.SecretBox;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
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

    public IdempotencyService(IdempotencyRecordRepository repository, SecretBox secretBox,
                              PlatformTransactionManager transactionManager,
                              @Value("${app.idempotency.ttl:PT24H}") Duration ttl) {
        this.repository = repository;
        this.secretBox = secretBox;
        this.tx = new TransactionTemplate(transactionManager);
        this.ttl = ttl;
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
        LocalDateTime now = LocalDateTime.now();
        Optional<IdempotencyRecord> existing = repository.findByOwnerAndIdempotencyKey(owner, key);
        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();
            if (record.getExpiresAt().isAfter(now)) {
                if (!record.getRequestHash().equals(requestHash)) {
                    return new Claim.Mismatch();
                }
                if (record.getStatus() == IdempotencyRecord.Status.IN_PROGRESS) {
                    return new Claim.InProgress();
                }
                return new Claim.Replay(record.getResponseStatus(),
                        record.getResponseBody() == null ? null : secretBox.decrypt(record.getResponseBody()),
                        record.getResponseContentType(), record.getResponseLocation());
            }
            repository.delete(record);
            repository.flush();
        }
        IdempotencyRecord record = new IdempotencyRecord();
        record.setOwner(owner);
        record.setIdempotencyKey(key);
        record.setRequestHash(requestHash);
        record.setStatus(IdempotencyRecord.Status.IN_PROGRESS);
        record.setCreatedAt(now);
        record.setExpiresAt(now.plus(ttl));
        return new Claim.Acquired(repository.saveAndFlush(record).getId());
    }

    public void complete(Long recordId, int status, String body, String contentType, String location) {
        tx.executeWithoutResult(s -> repository.findById(recordId).ifPresent(record -> {
            record.setStatus(IdempotencyRecord.Status.COMPLETED);
            record.setResponseStatus(status);
            record.setResponseBody(body == null || body.isEmpty() ? null : secretBox.encrypt(body));
            record.setResponseContentType(contentType);
            record.setResponseLocation(location);
        }));
    }

    /** Frees the key after a server error, so the client's retry runs the request again. */
    public void release(Long recordId) {
        tx.executeWithoutResult(s -> repository.deleteById(recordId));
    }

    @Scheduled(cron = "0 25 3 * * *", zone = "Asia/Kolkata")
    public void purgeExpired() {
        Integer deleted = tx.execute(s -> repository.deleteExpired(LocalDateTime.now()));
        log.info("Purged {} expired idempotency keys", deleted);
    }
}
