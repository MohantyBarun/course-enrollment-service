package com.seatsure.realtime;

import com.seatsure.entity.ShowSeatStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The browsers watching each show's seat map on this instance, as Server-Sent Events streams.
 * SSE rather than WebSockets: updates only flow one way, it's plain HTTP (works through proxies),
 * and browsers reconnect on their own.
 *
 * <p>Servlet async: an open stream holds no request thread, only a socket.
 */
@Slf4j
@Component
public class SeatStreams {

    /** Browsers reconnect on their own when this runs out (and refetch the map). */
    static final Duration STREAM_TIMEOUT = Duration.ofMinutes(30);

    public record SeatChange(Long seatId, ShowSeatStatus status) {
    }

    public record SeatsEvent(Long showId, List<SeatChange> changes) {
    }

    private final Map<Long, Set<SseEmitter>> byShow = new ConcurrentHashMap<>();

    public SeatStreams(MeterRegistry meters) {
        Gauge.builder("seatmap.streams.open", this, SeatStreams::openStreams)
                .description("Seat-map SSE streams open on this instance").register(meters);
    }

    public SseEmitter subscribe(Long showId) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
        Set<SseEmitter> watchers = byShow.computeIfAbsent(showId, id -> ConcurrentHashMap.newKeySet());
        watchers.add(emitter);
        Runnable remove = () -> {
            watchers.remove(emitter);
            byShow.computeIfPresent(showId, (id, set) -> set.isEmpty() ? null : set);
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        // Tells the client the stream is live; it then fetches the map (and applies events after it)
        send(emitter, SseEmitter.event().name("ready").data(Map.of("showId", showId)), remove);
        return emitter;
    }

    /** Fans a committed change out to this instance's watchers of the show. */
    void publish(SeatEvents.Payload payload) {
        Set<SseEmitter> watchers = byShow.get(payload.s());
        if (watchers == null || watchers.isEmpty()) {
            return;
        }
        SseEmitter.SseEventBuilder event;
        if (Boolean.TRUE.equals(payload.r()) || payload.c() == null) {
            event = SseEmitter.event().name("reload").data(Map.of("showId", payload.s()));
        } else {
            List<SeatChange> changes = payload.c().stream()
                    .map(pair -> new SeatChange(((Number) pair.get(0)).longValue(), SeatEvents.status((String) pair.get(1))))
                    .toList();
            event = SseEmitter.event().name("seats").data(new SeatsEvent(payload.s(), changes), MediaType.APPLICATION_JSON);
        }
        for (SseEmitter emitter : watchers) {
            send(emitter, event, () -> watchers.remove(emitter));
        }
    }

    /**
     * Tells every open seat map to refetch. Used after the LISTEN connection comes back: changes
     * committed while it was down were never delivered, so the maps can't be patched, only reloaded.
     */
    public int reloadAll() {
        int[] sent = {0};
        byShow.forEach((showId, watchers) -> {
            SseEmitter.SseEventBuilder event = SseEmitter.event().name("reload").data(Map.of("showId", showId));
            watchers.forEach(emitter -> {
                send(emitter, event, () -> watchers.remove(emitter));
                sent[0]++;
            });
        });
        return sent[0];
    }

    /** A comment line every 20 seconds, so proxies don't close streams that are quiet. */
    @Scheduled(fixedDelay = 20_000)
    void heartbeat() {
        byShow.values().forEach(watchers -> watchers.forEach(emitter ->
                send(emitter, SseEmitter.event().comment("keep-alive"), () -> watchers.remove(emitter))));
    }

    int openStreams() {
        return byShow.values().stream().mapToInt(Set::size).sum();
    }

    private static void send(SseEmitter emitter, SseEmitter.SseEventBuilder event, Runnable onFailure) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            // The browser went away (or the stream already completed)
            onFailure.run();
            emitter.completeWithError(e);
        }
    }
}
