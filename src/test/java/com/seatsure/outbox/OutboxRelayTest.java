package com.seatsure.outbox;

import java.time.Clock;
import java.time.Instant;
import com.seatsure.entity.OutboxEvent;
import com.seatsure.repository.OutboxEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock private OutboxEventRepository repository;
    @Mock private OutboxTransport transport;
    @Mock private PlatformTransactionManager transactionManager;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        relay = new OutboxRelay(repository, transport, transactionManager, meters, 2, 3, Duration.ofDays(7),
                Duration.ofMinutes(5), Clock.systemUTC());
        // The outcome is recorded on a fresh load of the event, in its own transaction
        lenient().when(repository.findById(any())).thenAnswer(i -> Optional.ofNullable(events.get(i.<UUID>getArgument(0))));
    }

    private final Map<UUID, OutboxEvent> events = new HashMap<>();

    private OutboxEvent event() {
        OutboxEvent e = OutboxEvent.pending("User", "1", OutboxEvents.USER_REGISTERED, "{}", Instant.now());
        events.put(e.getId(), e);
        return e;
    }

    private double relayed(String outcome) {
        return meters.counter("outbox.events.relayed", "outcome", outcome).count();
    }

    @Test
    void delivered_isMarkedPublished() throws Exception {
        OutboxEvent e = event();
        when(repository.lockNextBatch(any(), eq(2))).thenReturn(List.of(e));

        assertThat(relay.relayPending()).isEqualTo(1);

        verify(transport).send(argThat(m -> m.id().equals(e.getId()) && m.type().equals(e.getEventType())));
        assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.PUBLISHED);
        assertThat(e.getPublishedAt()).isNotNull();
        assertThat(relayed("published")).isEqualTo(1);
    }

    @Test
    void failedDelivery_isRetriedLaterWithBackoff() throws Exception {
        OutboxEvent e = event();
        when(repository.lockNextBatch(any(), eq(2))).thenReturn(List.of(e));
        doThrow(new IllegalStateException("broker down")).when(transport).send(any());

        relay.relayPending();

        assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.PENDING);
        assertThat(e.getAttempts()).isEqualTo(1);
        assertThat(e.getLastError()).contains("broker down");
        assertThat(e.getNextAttemptAt()).isAfter(Instant.now().plus(Duration.ofSeconds(3)));
        assertThat(relayed("retry")).isEqualTo(1);
    }

    @Test
    void oneFailure_doesntBlockTheRestOfTheBatch() throws Exception {
        OutboxEvent bad = event();
        OutboxEvent good = event();
        when(repository.lockNextBatch(any(), eq(2))).thenReturn(List.of(bad, good)).thenReturn(List.of());
        doThrow(new IllegalStateException("x")).doNothing().when(transport).send(any());

        relay.relayPending();

        assertThat(bad.getStatus()).isEqualTo(OutboxEvent.Status.PENDING);
        assertThat(good.getStatus()).isEqualTo(OutboxEvent.Status.PUBLISHED);
    }

    @Test
    void afterMaxAttempts_isMarkedFailed() throws Exception {
        OutboxEvent e = event();
        e.setAttempts(2); // max is 3
        when(repository.lockNextBatch(any(), eq(2))).thenReturn(List.of(e));
        doThrow(new IllegalStateException("still down")).when(transport).send(any());

        relay.relayPending();

        assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.FAILED);
        assertThat(relayed("failed")).isEqualTo(1);
    }

    @Test
    void keepsGoingWhileBatchesAreFull() throws Exception {
        when(repository.lockNextBatch(any(), eq(2)))
                .thenReturn(List.of(event(), event()))
                .thenReturn(List.of(event(), event()))
                .thenReturn(List.of(event()));

        assertThat(relay.relayPending()).isEqualTo(5);
        verify(repository, times(3)).lockNextBatch(any(), eq(2));
    }

    @Test
    void claimedEvents_areLeased_soOtherRelaysSkipThemWhileSending() throws Exception {
        OutboxEvent e = event();
        Instant[] leasedUntil = new Instant[1];
        when(repository.lockNextBatch(any(), eq(2))).thenReturn(List.of(e));
        doAnswer(i -> {
            leasedUntil[0] = e.getNextAttemptAt(); // what other relays see while this one sends
            return null;
        }).when(transport).send(any());

        relay.relayPending();

        assertThat(leasedUntil[0]).isAfter(Instant.now().plus(Duration.ofMinutes(4)));
        assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.PUBLISHED);
    }

    @Test
    @DisplayName("no transaction is open while the transport sends")
    void sending_happensOutsideAnyTransaction() throws Exception {
        OutboxEvent e = event();
        when(repository.lockNextBatch(any(), eq(2))).thenReturn(List.of(e));
        List<String> calls = new ArrayList<>();
        when(transactionManager.getTransaction(any())).thenAnswer(i -> {
            calls.add("begin");
            return new SimpleTransactionStatus();
        });
        doAnswer(i -> calls.add("commit")).when(transactionManager).commit(any());
        doAnswer(i -> calls.add("send")).when(transport).send(any());

        relay.relayPending();

        assertThat(calls).containsExactly("begin", "commit", "send", "begin", "commit");
    }

    @Test
    void anEventFinishedByAnotherRelay_isLeftAlone() throws Exception {
        OutboxEvent e = event();
        when(repository.lockNextBatch(any(), eq(2))).thenReturn(List.of(e));
        doAnswer(i -> {
            e.markPublished(Instant.now()); // our lease ran out; another relay sent and recorded it
            return null;
        }).when(transport).send(any());

        relay.relayPending();

        assertThat(relayed("published")).isZero();
        assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.PUBLISHED);
    }

    @Test
    void backoff_growsExponentiallyAndIsCapped() {
        OutboxEvent e = event();
        Instant now = Instant.now();
        List<Long> delays = IntStream.range(0, 12).mapToObj(i -> {
            e.markFailed("x", now, 100);
            return Duration.between(now, e.getNextAttemptAt()).toSeconds();
        }).toList();

        assertThat(delays.subList(0, 4)).containsExactly(5L, 10L, 20L, 40L);
        assertThat(delays.get(11)).isEqualTo(3600L);
    }
}
