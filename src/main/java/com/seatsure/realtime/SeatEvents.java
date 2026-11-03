package com.seatsure.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsure.entity.ShowSeat;
import com.seatsure.entity.ShowSeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;

/**
 * Announces seat changes with PostgreSQL NOTIFY, from inside the transaction that makes them.
 *
 * <p>Postgres delivers a notification only when its transaction commits, in commit order, and
 * never for one that rolls back. So a seat a customer failed to hold is never shown as taken, and
 * every app instance (each LISTENs, see {@link SeatChangeListener}) hears every change, whichever
 * instance made it.
 */
@Component
public class SeatEvents {

    public static final String CHANNEL = "seat_changes";
    /** NOTIFY payloads are limited to 8000 bytes; bigger changes tell clients to reload the map. */
    static final int MAX_PAYLOAD_BYTES = 7000;

    /** {@code c}: [seatId, "A"|"H"|"B"] pairs. {@code r}: reload the whole map instead. */
    public record Payload(Long s, List<List<Object>> c, Boolean r) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SeatEvents(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** The seats' current states (as set in this transaction) go out when it commits. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void seatsChanged(Long showId, Collection<ShowSeat> seats) {
        if (seats.isEmpty()) {
            return;
        }
        List<List<Object>> changes = seats.stream()
                .map(ss -> List.<Object>of(ss.getSeat().getId(), code(ss.getStatus())))
                .toList();
        notify(encode(new Payload(showId, changes, null), showId));
    }

    /** Something changed too broadly to list (e.g. the show was cancelled): clients refetch the map. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reload(Long showId) {
        notify(encode(new Payload(showId, null, true), showId));
    }

    String encode(Payload payload, Long showId) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            if (json.getBytes(StandardCharsets.UTF_8).length <= MAX_PAYLOAD_BYTES) {
                return json;
            }
            return objectMapper.writeValueAsString(new Payload(showId, null, true));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void notify(String json) {
        jdbc.queryForObject("SELECT pg_notify(?, ?)", Object.class, CHANNEL, json);
    }

    static String code(ShowSeatStatus status) {
        return switch (status) {
            case AVAILABLE -> "A";
            case HELD -> "H";
            case BOOKED -> "B";
        };
    }

    static ShowSeatStatus status(String code) {
        return switch (code) {
            case "A" -> ShowSeatStatus.AVAILABLE;
            case "H" -> ShowSeatStatus.HELD;
            case "B" -> ShowSeatStatus.BOOKED;
            default -> throw new IllegalArgumentException("Unknown seat status code " + code);
        };
    }
}
