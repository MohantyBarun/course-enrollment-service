package com.seatsure.realtime;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SeatStreamsTest {

    @Test
    void reloadAll_reachesEveryOpenStreamOfEveryShow() {
        SeatStreams streams = new SeatStreams(new SimpleMeterRegistry());
        streams.subscribe(1L);
        streams.subscribe(1L);
        streams.subscribe(2L);

        assertThat(streams.openStreams()).isEqualTo(3);
        assertThat(streams.reloadAll()).isEqualTo(3);
    }

    @Test
    void closeAll_endsEveryStream_soShutdownDoesntWaitOnThem() {
        SeatStreams streams = new SeatStreams(new SimpleMeterRegistry());
        streams.subscribe(1L);
        streams.subscribe(2L);

        streams.closeAll();

        assertThat(streams.openStreams()).isZero();
    }

    @Test
    void reloadAll_withNoStreams_isANoOp() {
        assertThat(new SeatStreams(new SimpleMeterRegistry()).reloadAll()).isZero();
    }
}
