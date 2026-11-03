package com.seatsure.integration;

import com.seatsure.exception.SeatsUnavailableException;
import com.seatsure.realtime.SeatChangeListener;
import com.seatsure.service.AdminCatalogService;
import com.seatsure.service.HoldService;
import com.seatsure.support.FullAppIntegrationTest;
import com.seatsure.support.TestFixtures;
import com.seatsure.support.TestFixtures.ShowFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The live seat map end to end: a browser-like client reads the SSE stream over a real port while
 * seats are held in other transactions; changes arrive only once (and only if) they commit.
 */
@FullAppIntegrationTest
class LiveSeatMapIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private HoldService holds;
    @Autowired private AdminCatalogService admin;
    @Autowired private TestFixtures fixtures;
    @Autowired private SeatChangeListener listener;
    @Autowired private JdbcTemplate jdbc;

    /** Collects "event:"/"data:" lines from the stream on a background thread. */
    private BlockingQueue<String> open(Long showId) throws Exception {
        await().atMost(Duration.ofSeconds(15)).until(listener::isListening);
        HttpResponse<InputStream> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/shows/" + showId + "/seats/stream"))
                        .header("Accept", "text/event-stream").build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).get().asString().startsWith("text/event-stream");
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        Thread.ofVirtual().start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("event:") || line.startsWith("data:")) {
                        lines.add(line);
                    }
                }
            } catch (Exception ignored) {
                // stream closed at the end of the test
            }
        });
        assertThat(lines.poll(10, TimeUnit.SECONDS)).isEqualTo("event:ready");
        lines.poll(5, TimeUnit.SECONDS); // its data line
        return lines;
    }

    @Test
    @DisplayName("a hold made elsewhere shows up on an open seat map; a failed hold never does")
    void committedHolds_arePushed_failedOnesAreNot() throws Exception {
        ShowFixture show = fixtures.show(1, 6);
        List<Long> seats = show.seatIds();
        BlockingQueue<String> stream = open(show.showId());

        holds.create(fixtures.customer().user().id(), show.showId(), seats.subList(0, 2));

        assertThat(stream.poll(10, TimeUnit.SECONDS)).isEqualTo("event:seats");
        String data = stream.poll(5, TimeUnit.SECONDS);
        assertThat(data).startsWith("data:")
                .contains("\"seatId\":" + seats.get(0)).contains("\"seatId\":" + seats.get(1)).contains("\"HELD\"");

        // Rolls back with a 409: nothing is announced
        Long other = fixtures.customer().user().id();
        assertThatThrownBy(() -> holds.create(other, show.showId(), seats.subList(1, 3)))
                .isInstanceOf(SeatsUnavailableException.class);
        assertThat(stream.poll(2, TimeUnit.SECONDS)).as("no event for a rolled-back change").isNull();
    }

    @Test
    @DisplayName("a cancelled show tells open seat maps to reload")
    void cancelledShow_triggersReload() throws Exception {
        ShowFixture show = fixtures.show(1, 4);
        BlockingQueue<String> stream = open(show.showId());

        admin.cancelShow(show.showId(), "Projector failure");

        assertThat(stream.poll(10, TimeUnit.SECONDS)).isEqualTo("event:reload");
    }

    @Test
    @DisplayName("when the LISTEN connection drops, open seat maps are told to reload once it's back")
    void listenerReconnect_triggersReload() throws Exception {
        ShowFixture show = fixtures.show(1, 4);
        BlockingQueue<String> stream = open(show.showId());

        // What a database failover or idle-connection reaper does to the listener's connection
        int killed = jdbc.queryForObject("""
                SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity
                WHERE query ILIKE 'LISTEN %' AND pid <> pg_backend_pid()
                """, Integer.class);
        assertThat(killed).isEqualTo(1);

        assertThat(stream.poll(20, TimeUnit.SECONDS)).isEqualTo("event:reload");
        await().atMost(Duration.ofSeconds(15)).until(listener::isListening);
    }
}
