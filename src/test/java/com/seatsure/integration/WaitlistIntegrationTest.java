package com.seatsure.integration;

import com.seatsure.exception.BadRequestException;
import com.seatsure.outbox.OutboxEvents;
import com.seatsure.payment.PaymentService;
import com.seatsure.service.BookingService;
import com.seatsure.service.HoldService;
import com.seatsure.service.WaitlistService;
import com.seatsure.support.FullAppIntegrationTest;
import com.seatsure.support.TestFixtures;
import com.seatsure.support.TestFixtures.ShowFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Freed seats go to the waitlist, in order, before anyone else can take them. */
@FullAppIntegrationTest
class WaitlistIntegrationTest {

    @Autowired private HoldService holds;
    @Autowired private PaymentService payments;
    @Autowired private BookingService bookings;
    @Autowired private WaitlistService waitlist;
    @Autowired private TestFixtures fixtures;
    @Autowired private JdbcTemplate jdbc;

    private Map<String, Object> entry(Long id) {
        return jdbc.queryForMap("SELECT status, hold_id FROM waitlist_entries WHERE id = ?", id);
    }

    @Test
    @DisplayName("joining is only for sold-out shows")
    void join_whileSeatsAreAvailable_isRejected() {
        ShowFixture show = fixtures.show(1, 2);
        Long customer = fixtures.customer().user().id();

        assertThatThrownBy(() -> waitlist.join(customer, show.showId(), 1)).isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("an expired hold's seats are offered to the first waiting customer whose request fits; if they don't pay, the next one gets them")
    void expiredHold_cascadesThroughTheQueue() {
        ShowFixture show = fixtures.show(1, 2); // two seats
        Long holder = fixtures.customer().user().id();
        Long wantsTwo = fixtures.customer().user().id();
        Long wantsThree = fixtures.customer().user().id();
        Long wantsOne = fixtures.customer().user().id();

        var original = holds.create(holder, show.showId(), show.seatIds()); // sold out
        Long first = waitlist.join(wantsTwo, show.showId(), 2).id();
        Long tooBig = waitlist.join(wantsThree, show.showId(), 3).id();
        Long third = waitlist.join(wantsOne, show.showId(), 1).id();

        fixtures.ageOut(original.holdId());
        holds.expireDue();

        // Both seats went straight to the first entry: nobody else could take them in between
        assertThat(entry(first).get("status")).isEqualTo("OFFERED");
        Long offeredHold = ((Number) entry(first).get("hold_id")).longValue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM show_seats WHERE hold_id = ?", Long.class, offeredHold))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM show_seats WHERE show_id = ? AND status = 'AVAILABLE'",
                Long.class, show.showId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type = ? AND aggregate_id = ?",
                Long.class, OutboxEvents.SEATS_OFFERED, String.valueOf(offeredHold))).isEqualTo(1);

        // The offer lapses: the next entry that fits (one seat) gets one, the other seat goes on sale
        fixtures.ageOut(offeredHold);
        holds.expireDue();

        assertThat(entry(first).get("status")).isEqualTo("LAPSED");
        assertThat(entry(tooBig).get("status")).as("wants more seats than came free").isEqualTo("WAITING");
        assertThat(entry(third).get("status")).isEqualTo("OFFERED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM show_seats WHERE show_id = ? AND status = 'AVAILABLE'",
                Long.class, show.showId())).isEqualTo(1);
    }

    @Test
    @DisplayName("paying for a waitlist offer fulfils the entry; a cancelled booking's seats go to the queue")
    void waitlistOffer_paidAndCancellationFeedsQueue() {
        ShowFixture show = fixtures.show(1, 2);
        Long buyer = fixtures.customer().user().id();
        Long waiting = fixtures.customer().user().id();

        String reference = fixtures.book(buyer, show.showId(), show.seatIds());
        Long entryId = waitlist.join(waiting, show.showId(), 2).id();

        bookings.cancel(buyer, reference, "Plans changed");

        assertThat(entry(entryId).get("status")).isEqualTo("OFFERED");
        Long holdId = ((Number) entry(entryId).get("hold_id")).longValue();
        var payment = payments.start(waiting, holdId);
        payments.onSucceeded(payment.gatewayRef(), payment.amountPaise());

        assertThat(entry(entryId).get("status")).isEqualTo("FULFILLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bookings WHERE show_id = ? AND user_id = ? AND status = 'CONFIRMED'",
                Long.class, show.showId(), waiting)).isEqualTo(1);
    }
}
