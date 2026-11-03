package com.seatsure.payment;

import com.seatsure.dto.CheckoutDtos.PaymentStarted;
import com.seatsure.entity.*;
import com.seatsure.exception.BadRequestException;
import com.seatsure.exception.ConflictException;
import com.seatsure.exception.ResourceNotFoundException;
import com.seatsure.outbox.OutboxEvents;
import com.seatsure.outbox.OutboxService;
import com.seatsure.payment.mock.MockPaymentGateway;
import com.seatsure.repository.PaymentRepository;
import com.seatsure.repository.SeatHoldRepository;
import com.seatsure.repository.ShowRepository;
import com.seatsure.service.BookingService;
import com.seatsure.service.WaitlistService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Paying for a hold. Starting a payment only registers it with the gateway; the outcome arrives
 * later as a webhook ({@link #onSucceeded} / {@link #onFailed}), possibly twice, possibly after the
 * hold has lapsed.
 *
 * <p>Lock order matches the rest of checkout: show (shared) -> hold -> payment -> seats. The
 * payment row is read without a lock first, only to learn which show and hold to lock.
 */
@Slf4j
@Service
public class PaymentService {

    private final SeatHoldRepository holds;
    private final PaymentRepository payments;
    private final ShowRepository shows;
    private final PaymentGateway gateway;
    private final BookingService bookings;
    private final WaitlistService waitlist;
    private final Refunds refunds;
    private final OutboxService outbox;
    private final Clock clock;
    private final MeterRegistry meters;

    public PaymentService(SeatHoldRepository holds, PaymentRepository payments, ShowRepository shows,
                          PaymentGateway gateway, BookingService bookings, WaitlistService waitlist, Refunds refunds,
                          OutboxService outbox, Clock clock, MeterRegistry meters) {
        this.holds = holds;
        this.payments = payments;
        this.shows = shows;
        this.gateway = gateway;
        this.bookings = bookings;
        this.waitlist = waitlist;
        this.refunds = refunds;
        this.outbox = outbox;
        this.clock = clock;
        this.meters = meters;
    }

    /** Starts paying for a live hold. If a payment is already in flight, that one is returned. */
    @Transactional
    public PaymentStarted start(Long userId, Long holdId) {
        Instant now = clock.instant();
        SeatHold hold = holds.findByIdForUpdate(holdId)
                .filter(h -> h.getUser().getId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Hold not found"));
        if (!hold.isLive(now)) {
            throw new ConflictException(hold.getStatus() == SeatHold.Status.CONVERTED
                    ? "These seats are already paid for" : "Your hold on these seats has ended. Please pick seats again.");
        }
        Payment pending = payments.findPending(holdId).orElse(null);
        if (pending != null) {
            return new PaymentStarted(pending.getGatewayRef(), pending.getAmountPaise(), pending.getStatus(),
                    "/pay/" + pending.getGatewayRef());
        }
        Payment payment = payments.save(new Payment(hold, hold.getUser(), MockPaymentGateway.newPaymentRef(),
                hold.totalPaise(), now));
        String checkout = gateway.createPayment(payment.getGatewayRef(), payment.getAmountPaise(),
                hold.getSeatCount() + " ticket(s), hold " + holdId);
        return new PaymentStarted(payment.getGatewayRef(), payment.getAmountPaise(), payment.getStatus(), checkout);
    }

    /**
     * The money was taken. If the hold is still live, its seats become a booking; otherwise (the
     * hold lapsed, or the show was cancelled, before the webhook got here) the money goes back.
     */
    @Transactional
    public void onSucceeded(String gatewayRef, int amountPaise) {
        Instant now = clock.instant();
        Locked locked = lock(gatewayRef);
        Payment payment = locked.payment();
        if (payment.getStatus() != Payment.Status.PENDING) {
            log.info("Payment {} is already {}; ignoring payment.succeeded", gatewayRef, payment.getStatus());
            return;
        }
        if (amountPaise != payment.getAmountPaise()) {
            // Never confirm on a mismatched amount: flag it and refund what was taken
            payment.moveTo(Payment.Status.SUCCEEDED, null, now);
            refunds.refund(payment, "Amount mismatch (" + amountPaise + " paid, " + payment.getAmountPaise() + " due)", now);
            log.error("Payment {} amount mismatch: got {}, expected {}", gatewayRef, amountPaise, payment.getAmountPaise());
            return;
        }
        payment.moveTo(Payment.Status.SUCCEEDED, null, now);
        SeatHold hold = locked.hold();
        Show show = locked.show();

        if (hold.isLive(now) && show.isBookable(now)) {
            Booking booking = bookings.createFromHold(hold, payment, now);
            hold.setBooking(booking);
            hold.close(SeatHold.Status.CONVERTED, now);
            waitlist.holdEnded(hold, true, now);
            meters.counter("payments.outcome", "result", "confirmed").increment();
            log.info("Payment {} confirmed hold {} as booking {}", gatewayRef, hold.getId(), booking.getReference());
            return;
        }

        String reason = show.getStatus() == ShowStatus.CANCELLED ? "the show was cancelled"
                : !show.isBookable(now) ? "the show had already started"
                : "your 10-minute hold on the seats ended before the payment came through";
        refunds.refund(payment, reason, now);
        User user = hold.getUser();
        outbox.record("Payment", payment.getId(), OutboxEvents.PAYMENT_REFUNDED, new OutboxEvents.PaymentRefunded(
                gatewayRef, user.getName(), user.getEmail(), show.getMovie().getTitle(), payment.getAmountPaise(),
                reason));
        meters.counter("payments.outcome", "result", "refunded-late").increment();
        log.warn("Payment {} arrived for hold {} in state {}; refunded", gatewayRef, hold.getId(), hold.getStatus());
    }

    /** The payment didn't go through. The hold stays live, so the customer can try again before it ends. */
    @Transactional
    public void onFailed(String gatewayRef, String reason) {
        Payment payment = lock(gatewayRef).payment();
        if (payment.getStatus() != Payment.Status.PENDING) {
            log.info("Payment {} is already {}; ignoring payment.failed", gatewayRef, payment.getStatus());
            return;
        }
        payment.moveTo(Payment.Status.FAILED, reason == null ? "Payment failed" : reason, clock.instant());
        meters.counter("payments.outcome", "result", "failed").increment();
    }

    /** Owner check for the mock payment page. */
    @Transactional(readOnly = true)
    public Payment pendingFor(Long userId, String gatewayRef) {
        Payment payment = payments.findByGatewayRef(gatewayRef)
                .filter(p -> p.getUser().getId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        if (payment.getStatus() != Payment.Status.PENDING) {
            throw new BadRequestException("This payment is already " + payment.getStatus().name().toLowerCase());
        }
        return payment;
    }

    private record Locked(Show show, SeatHold hold, Payment payment) {
    }

    /** Ids first (scalars, nothing cached), then lock show -> hold -> payment and load them fresh. */
    private Locked lock(String gatewayRef) {
        Object[] ids = payments.findHoldAndShowIds(gatewayRef).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Unknown payment " + gatewayRef));
        Long holdId = (Long) ids[0];
        Long showId = (Long) ids[1];
        shows.lockForShare(showId);
        Show show = shows.findDetailed(showId).orElseThrow();
        SeatHold hold = holds.findByIdForUpdate(holdId).orElseThrow();
        Payment payment = payments.findByGatewayRefForUpdate(gatewayRef).orElseThrow();
        return new Locked(show, hold, payment);
    }
}
