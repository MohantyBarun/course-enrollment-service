package com.student.studentservice.outbox;

import com.student.studentservice.entity.OutboxEvent;
import com.student.studentservice.repository.OutboxEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
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
        relay = new OutboxRelay(repository, transport, transactionManager, meters, 2, 3, Duration.ofDays(7));
    }

    private static OutboxEvent event() {
        return OutboxEvent.pending("Student", "1", OutboxEvents.STUDENT_REGISTERED, "{}", LocalDateTime.now());
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
        assertThat(e.getNextAttemptAt()).isAfter(LocalDateTime.now().plusSeconds(3));
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
    void backoff_growsExponentiallyAndIsCapped() {
        OutboxEvent e = event();
        LocalDateTime now = LocalDateTime.now();
        List<Long> delays = IntStream.range(0, 12).mapToObj(i -> {
            e.markFailed("x", now, 100);
            return Duration.between(now, e.getNextAttemptAt()).toSeconds();
        }).toList();

        assertThat(delays.subList(0, 4)).containsExactly(5L, 10L, 20L, 40L);
        assertThat(delays.get(11)).isEqualTo(3600L);
    }
}
