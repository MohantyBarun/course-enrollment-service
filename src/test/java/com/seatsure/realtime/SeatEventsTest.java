package com.seatsure.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsure.entity.ShowSeatStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SeatEventsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SeatEvents events = new SeatEvents(null, mapper);

    @Test
    void smallChange_isSentSeatBySeat() throws Exception {
        String json = events.encode(new SeatEvents.Payload(7L, List.of(List.of(101L, "H"), List.of(102L, "H")), null), 7L);

        assertThat(json).isEqualTo("{\"s\":7,\"c\":[[101,\"H\"],[102,\"H\"]],\"r\":null}");
        assertThat(mapper.readValue(json, SeatEvents.Payload.class).c()).hasSize(2);
    }

    @Test
    void changeTooBigForNotify_becomesAReload() {
        List<List<Object>> many = new ArrayList<>();
        for (long id = 1_000_000; id < 1_001_000; id++) {
            many.add(List.of(id, "A"));
        }

        assertThat(events.encode(new SeatEvents.Payload(7L, many, null), 7L)).isEqualTo("{\"s\":7,\"c\":null,\"r\":true}");
    }

    @Test
    void statusCodesRoundTrip() {
        for (ShowSeatStatus status : ShowSeatStatus.values()) {
            assertThat(SeatEvents.status(SeatEvents.code(status))).isEqualTo(status);
        }
    }
}
