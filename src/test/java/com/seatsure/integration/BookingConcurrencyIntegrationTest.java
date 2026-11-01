package com.seatsure.integration;

import com.seatsure.exception.SeatsUnavailableException;
import com.seatsure.service.AdminCatalogService;
import com.seatsure.service.BookingService;
import com.seatsure.support.FullAppIntegrationTest;
import com.seatsure.support.TestFixtures;
import com.seatsure.support.TestFixtures.ShowFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Many customers racing for the same seats, against real PostgreSQL row locks. These are the
 * tests that prove the core promise: a seat is never sold twice, and overlapping requests never
 * deadlock.
 */
@FullAppIntegrationTest
class BookingConcurrencyIntegrationTest {

    private static final int THREADS = 30;

    @Autowired private BookingService bookings;
    @Autowired private AdminCatalogService admin;
    @Autowired private TestFixtures fixtures;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("30 customers click the same seat at the same moment: exactly one gets it")
    void sameSeat_exactlyOneWinner() throws Exception {
        ShowFixture show = fixtures.show(2, 10);
        Long seat = show.seatIds().get(5);
        List<Long> customers = customers(THREADS);

        Outcomes outcomes = race(customers.size(), i -> bookings.book(customers.get(i), show.showId(), List.of(seat)));

        assertThat(outcomes.succeeded.get()).isEqualTo(1);
        assertThat(outcomes.seatsTaken.get()).isEqualTo(THREADS - 1);
        assertThat(outcomes.unexpected).isEmpty();
        assertThat(confirmedBookings(show.showId())).isEqualTo(1);
        assertInventoryConsistent(show.showId());
    }

    @Test
    @DisplayName("overlapping multi-seat requests in random order: no seat sold twice, no deadlocks")
    void overlappingSeatSets_noDoubleSaleAndNoDeadlock() throws Exception {
        ShowFixture show = fixtures.show(2, 10); // 20 seats, 30 buyers wanting 2-4 each: heavy overlap
        List<Long> seatIds = show.seatIds();
        List<Long> customers = customers(THREADS);
        Random random = new Random(42);
        List<List<Long>> requests = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            List<Long> shuffled = new ArrayList<>(seatIds);
            Collections.shuffle(shuffled, random);
            requests.add(shuffled.subList(0, 2 + random.nextInt(3))); // ids in random order, not sorted
        }

        Outcomes outcomes = race(THREADS, i -> bookings.book(customers.get(i), show.showId(), requests.get(i)));

        assertThat(outcomes.unexpected).as("no deadlocks, lock timeouts or other errors").isEmpty();
        assertThat(outcomes.succeeded.get()).isPositive();
        assertThat(outcomes.succeeded.get() + outcomes.seatsTaken.get()).isEqualTo(THREADS);
        assertInventoryConsistent(show.showId());
        Long sold = jdbc.queryForObject("SELECT COUNT(*) FROM booking_seats bs JOIN bookings b ON b.id = bs.booking_id "
                + "WHERE b.show_id = ? AND b.status = 'CONFIRMED'", Long.class, show.showId());
        Long distinctSold = jdbc.queryForObject("SELECT COUNT(DISTINCT bs.seat_id) FROM booking_seats bs "
                + "JOIN bookings b ON b.id = bs.booking_id WHERE b.show_id = ? AND b.status = 'CONFIRMED'",
                Long.class, show.showId());
        assertThat(sold).as("every sold seat was sold once").isEqualTo(distinctSold).isLessThanOrEqualTo(20);
    }

    @Test
    @DisplayName("a cancelled booking's seats can be booked again")
    void cancellation_releasesSeats() {
        ShowFixture show = fixtures.show(1, 6);
        List<Long> seats = show.seatIds().subList(0, 2);
        Long first = fixtures.customer().user().id();
        Long second = fixtures.customer().user().id();

        var booking = bookings.book(first, show.showId(), seats);
        bookings.cancel(first, booking.reference(), null);
        var again = bookings.book(second, show.showId(), seats);

        assertThat(again.seats()).hasSize(2);
        assertInventoryConsistent(show.showId());
    }

    @Test
    @DisplayName("a show cancelled while customers are booking ends with no confirmed bookings")
    void showCancellation_racingBookings_leavesNothingConfirmed() throws Exception {
        ShowFixture show = fixtures.show(3, 10);
        List<Long> customers = customers(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS + 1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                int n = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        bookings.book(customers.get(n), show.showId(), List.of(show.seatIds().get(n)));
                    } catch (RuntimeException ignored) {
                        // closed, or cancelled before this booking got the lock
                    }
                    return null;
                }));
            }
            futures.add(pool.submit(() -> {
                start.await();
                admin.cancelShow(show.showId(), "Projector failure");
                return null;
            }));
            start.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(confirmedBookings(show.showId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM show_seats WHERE show_id = ? AND status <> 'AVAILABLE'",
                Long.class, show.showId())).isZero();
    }

    // ---------------------------------------------------------------- helpers

    private List<Long> customers(int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(fixtures.customer().user().id());
        }
        return ids;
    }

    private long confirmedBookings(Long showId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM bookings WHERE show_id = ? AND status = 'CONFIRMED'",
                Long.class, showId);
    }

    /** show_seats agrees with the bookings: booked seats == seats of confirmed bookings, seat for seat. */
    private void assertInventoryConsistent(Long showId) {
        Long mismatches = jdbc.queryForObject("""
                SELECT COUNT(*) FROM show_seats ss
                FULL JOIN (SELECT bs.seat_id, b.id AS booking_id FROM booking_seats bs
                           JOIN bookings b ON b.id = bs.booking_id
                           WHERE b.show_id = ? AND b.status = 'CONFIRMED') sold
                  ON sold.seat_id = ss.seat_id
                WHERE ss.show_id = ? AND ((ss.status = 'BOOKED') <> (sold.booking_id IS NOT NULL)
                   OR ss.booking_id IS DISTINCT FROM sold.booking_id)
                """, Long.class, showId, showId);
        assertThat(mismatches).as("seat inventory matches confirmed bookings").isZero();
    }

    private interface Attempt {
        void run(int index);
    }

    private static final class Outcomes {
        final AtomicInteger succeeded = new AtomicInteger();
        final AtomicInteger seatsTaken = new AtomicInteger();
        final List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());
    }

    /** Starts every attempt at the same moment (latch) and classifies the outcomes. */
    private static Outcomes race(int count, Attempt attempt) throws Exception {
        Outcomes outcomes = new Outcomes();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int n = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        attempt.run(n);
                        outcomes.succeeded.incrementAndGet();
                    } catch (SeatsUnavailableException e) {
                        outcomes.seatsTaken.incrementAndGet();
                    } catch (Throwable t) {
                        outcomes.unexpected.add(t);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        return outcomes;
    }
}
