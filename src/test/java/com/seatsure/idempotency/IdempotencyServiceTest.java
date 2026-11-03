package com.seatsure.idempotency;

import com.seatsure.entity.IdempotencyRecord;
import com.seatsure.idempotency.IdempotencyService.Claim;
import com.seatsure.repository.IdempotencyRecordRepository;
import com.seatsure.util.SecretBox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class IdempotencyServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");
    private static final String SECRET = "integration-test-secret-that-is-longer-than-sixty-four-bytes-for-hs512-signing";

    private final IdempotencyRecordRepository repository = mock(IdempotencyRecordRepository.class);
    private final SecretBox secretBox = new SecretBox("", SECRET);
    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        service = new IdempotencyService(repository, secretBox, mock(PlatformTransactionManager.class),
                Duration.ofHours(24), Duration.ofMinutes(2), Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.saveAndFlush(any())).thenAnswer(i -> {
            IdempotencyRecord r = i.getArgument(0);
            ReflectionTestUtils.setField(r, "id", 99L);
            return r;
        });
    }

    private IdempotencyRecord existing(IdempotencyRecord.Status status, Duration age) {
        IdempotencyRecord r = IdempotencyRecord.inProgress("asha@x.com", "key-12345678", "hash", NOW.minus(age),
                NOW.minus(age).plus(Duration.ofHours(24)));
        ReflectionTestUtils.setField(r, "id", 5L);
        if (status == IdempotencyRecord.Status.COMPLETED) {
            r.complete(201, secretBox.encrypt("{\"holdId\":1}"), null, null);
        }
        when(repository.findByOwnerAndIdempotencyKey("asha@x.com", "key-12345678")).thenReturn(Optional.of(r));
        return r;
    }

    private Claim claim(String hash) {
        return service.claim("asha@x.com", "key-12345678", hash);
    }

    @Test
    void newKey_isAcquired() {
        assertThat(claim("hash")).isEqualTo(new Claim.Acquired(99L));
    }

    @Test
    @DisplayName("a request still running (seconds old) makes a retry wait: 409, not a second run")
    void recentInProgress_isInProgress() {
        existing(IdempotencyRecord.Status.IN_PROGRESS, Duration.ofSeconds(10));

        assertThat(claim("hash")).isInstanceOf(Claim.InProgress.class);
        verify(repository, never()).deleteRecord(any());
    }

    @Test
    @DisplayName("a key left in progress by an instance that died is reclaimed instead of blocking retries for a day")
    void abandonedInProgress_isReclaimed() {
        existing(IdempotencyRecord.Status.IN_PROGRESS, Duration.ofMinutes(3));
        when(repository.deleteRecord(5L)).thenReturn(1);

        assertThat(claim("hash")).isEqualTo(new Claim.Acquired(99L));
        verify(repository).deleteRecord(5L);
    }

    @Test
    @DisplayName("two retries reclaiming the same key: the one whose delete found nothing backs off")
    void abandonedInProgress_lostRace_isInProgress() {
        existing(IdempotencyRecord.Status.IN_PROGRESS, Duration.ofMinutes(3));
        when(repository.deleteRecord(5L)).thenReturn(0);

        assertThat(claim("hash")).isInstanceOf(Claim.InProgress.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void completed_isReplayed_evenWhenOld() {
        existing(IdempotencyRecord.Status.COMPLETED, Duration.ofHours(3));

        assertThat(claim("hash")).isEqualTo(new Claim.Replay(201, "{\"holdId\":1}", null, null));
    }

    @Test
    void differentRequestWithTheSameKey_isAMismatch_evenIfAbandoned() {
        existing(IdempotencyRecord.Status.IN_PROGRESS, Duration.ofMinutes(3));

        assertThat(claim("other-hash")).isInstanceOf(Claim.Mismatch.class);
        verify(repository, never()).deleteRecord(any());
    }

    @Test
    void expiredKey_isReplacedByANewClaim() {
        existing(IdempotencyRecord.Status.COMPLETED, Duration.ofHours(25));
        when(repository.deleteRecord(5L)).thenReturn(1);

        assertThat(claim("other-hash")).isEqualTo(new Claim.Acquired(99L));
    }
}
