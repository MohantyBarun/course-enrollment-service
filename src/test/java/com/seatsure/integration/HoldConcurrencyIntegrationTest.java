package com.seatsure.integration;

import com.seatsure.exception.SeatsUnavailableException;
import com.seatsure.service.AdminCatalogService;
import com.seatsure.service.BookingService;
import com.seatsure.service.HoldService;
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
 * Many customers racing for the same seats, against real PostgreSQL row locks. Holding seats is the
 * step customers race on (a booking only ever comes from a paid hold), so these tests prove the core
 * promise: a seat is never held or sold twice, and overlapping requests never deadlock.
 */
@FullAppIntegrationTest
class HoldConcurrencyIntegrationTest {

    private static final int THREADS = 30;

    @Autowired private HoldService holds;
    @Autowired private BookingService bookings;
    @Autowired private AdminCatalogService admin;
    @Autowired private TestFixtures fixtures;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("30 customers grab the same seat at the same moment: exactly one holds it")
    void sameSeat_exactlyOneWinner() throws Exception {
        ShowFixture show = fixtures.show(2, 10);
        Long seat = show.seatIds().get(5);
        List<Long> customers = customers(THREADS);

        Outcomes outcomes = race(THREADS, i -> holds.create(customers.get(i), show.showId(), List.of(seat)));

        assertThat(outcomes.succeeded.get()).isEqualTo(1);
        assertThat(outcomes.seatsTaken.get()).isEqualTo(THREADS - 1);
        assertThat(outcomes.unexpected).isEmpty();
        assertThat(activeHolds(show.showId())).isEqualTo(1);
        assertInventoryConsistent(show.showId());
    }

    @Test
    @DisplayName("overlapping multi-seat requests in random order: no seat held twice, no deadlocks")
    void overlappingSeatSets_noDoubleHoldAndNoDeadlock() throws Exception {
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

        Outcomes outcomes = race(THREADS, i -> holds.create(customers.get(i), show.showId(), requests.get(i)));

        assertThat(outcomes.unexpected).as("no deadlocks, lock timeouts or other errors").isEmpty();
        assertThat(outcomes.succeeded.get()).isPositive();
        assertThat(outcomes.succeeded.get() + outcomes.seatsTaken.get()).isEqualTo(THREADS);
        Long heldSeats = jdbc.queryForObject("SELECT COALESCE(SUM(seat_count), 0) FROM seat_holds "
                + "WHERE show_id = ? AND status = 'ACTIVE'", Long.class, show.showId());
        assertThat(heldSeats).as("seats held == seats marked HELD, so none is held twice").isEqualTo(
                jdbc.queryForObject("SELECT COUNT(*) FROM show_seats WHERE show_id = ? AND status = 'HELD'",
                        Long.class, show.showId())).isLessThanOrEqualTo(20L);
        assertInventoryConsistent(show.showId());
    }

    @Test
    @DisplayName("a paid hold becomes a booking; cancelling it refunds and frees the seats for others")
    void bookingAndCancellation() {
        ShowFixture show = fixtures.show(1, 6);
        List<Long> seats = show.seatIds().subList(0, 2);
        Long first = fixtures.customer().user().id();
        Long second = fixtures.customer().user().id();

        String reference = fixtures.book(first, show.showId(), seats);
        assertThat(reference).startsWith("SS");
        assertInventoryConsistent(show.showId());

        bookings.cancel(first, reference, null);
        assertThat(jdbc.queryForObject("SELECT p.status FROM payments p JOIN bookings b ON b.payment_id = p.id "
                + "WHERE b.reference = ?", String.class, reference)).isEqualTo("REFUNDED");
        assertThat(fixtures.book(second, show.showId(), seats)).isNotEqualTo(reference);
        assertInventoryConsistent(show.showId());
    }

    @Test
    @DisplayName("choosing seats again replaces your hold: kept seats stay yours, dropped ones are freed")
    void holdingAgain_replacesThePreviousHold() {
        ShowFixture show = fixtures.show(1, 6);
        List<Long> seats = show.seatIds();
        Long customer = fixtures.customer().user().id();

        var first = holds.create(customer, show.showId(), seats.subList(0, 3));
        var second = holds.create(customer, show.showId(), seats.subList(2, 5));

        assertThat(jdbc.queryForObject("SELECT status FROM seat_holds WHERE id = ?", String.class, first.holdId()))
                .isEqualTo("RELEASED");
        assertThat(activeHolds(show.showId())).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT seat_id FROM show_seats WHERE hold_id = ? ORDER BY seat_id", Long.class,
                second.holdId())).containsExactlyElementsOf(seats.subList(2, 5));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM show_seats WHERE show_id = ? AND status = 'AVAILABLE'",
                Long.class, show.showId())).isEqualTo(3);
    }

    @Test
    @DisplayName("an expired hold's seats go back on sale")
    void expiredHold_releasesSeats() {
        ShowFixture show = fixtures.show(1, 4);
        Long customer = fixtures.customer().user().id();
        var hold = holds.create(customer, show.showId(), show.seatIds().subList(0, 2));

        fixtures.ageOut(hold.holdId());
        holds.expireDue();

        assertThat(jdbc.queryForObject("SELECT status FROM seat_holds WHERE id = ?", String.class, hold.holdId()))
                .isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM show_seats WHERE show_id = ? AND status = 'AVAILABLE'",
                Long.class, show.showId())).isEqualTo(4);
    }

    @Test
    @DisplayName("a show cancelled while customers are holding seats ends with nothing held")
    void showCancellation_racingHolds_leavesNothingHeld() throws Exception {
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
                        holds.create(customers.get(n), show.showId(), List.of(show.seatIds().get(n)));
                    } catch (RuntimeException ignored) {
                        // closed, or cancelled before this hold got the lock
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

        assertThat(activeHolds(show.showId())).isZero();
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

    private long activeHolds(Long showId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM seat_holds WHERE show_id = ? AND status = 'ACTIVE'",
                Long.class, showId);
    }

    /**
     * show_seats agrees with holds and bookings, seat for seat: HELD seats belong to active holds,
     * BOOKED seats to confirmed bookings (which list exactly those seats).
     */
    private void assertInventoryConsistent(Long showId) {
        Long badHeld = jdbc.queryForObject("""
                SELECT COUNT(*) FROM show_seats ss LEFT JOIN seat_holds h ON h.id = ss.hold_id
                WHERE ss.show_id = ? AND ss.status = 'HELD' AND (h.status IS DISTINCT FROM 'ACTIVE')
                """, Long.class, showId);
        Long badBooked = jdbc.queryForObject("""
                SELECT COUNT(*) FROM show_seats ss
                FULL JOIN (SELECT bs.seat_id, b.id AS booking_id FROM booking_seats bs
                           JOIN bookings b ON b.id = bs.booking_id
                           WHERE b.show_id = ? AND b.status = 'CONFIRMED') sold
                  ON sold.seat_id = ss.seat_id
                WHERE ss.show_id = ? AND ((ss.status = 'BOOKED') <> (sold.booking_id IS NOT NULL)
                   OR (ss.status = 'BOOKED' AND ss.booking_id IS DISTINCT FROM sold.booking_id))
                """, Long.class, showId, showId);
        assertThat(badHeld).as("HELD seats belong to active holds").isZero();
        assertThat(badBooked).as("BOOKED seats match confirmed bookings").isZero();
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
