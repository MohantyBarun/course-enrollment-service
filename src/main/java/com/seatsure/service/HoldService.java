package com.seatsure.service;

import com.seatsure.dto.CheckoutDtos.HeldSeat;
import com.seatsure.dto.CheckoutDtos.HoldView;
import com.seatsure.dto.CheckoutDtos.PaymentSummary;
import com.seatsure.entity.*;
import com.seatsure.exception.BadRequestException;
import com.seatsure.exception.ConflictException;
import com.seatsure.exception.ResourceNotFoundException;
import com.seatsure.exception.SeatsUnavailableException;
import com.seatsure.realtime.SeatEvents;
import com.seatsure.repository.*;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.annotation.Observed;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Picking seats holds them for a few minutes while the customer pays.
 *
 * <p>Creating a hold locks the requested seats ({@code SELECT ... FOR UPDATE}, in id order so
 * overlapping requests queue instead of deadlocking), checks every one is still AVAILABLE and
 * marks them HELD, all in one transaction. A competing request for any of the same seats waits
 * for the lock, then sees them HELD and gets a 409 naming the taken seats.
 *
 * <p>Lock order everywhere: show (shared) -> hold -> seats -> waitlist entries. Show cancellation
 * takes the show exclusively first, so it waits for holds in flight and blocks new ones.
 */
@Slf4j
@Service
public class HoldService {

    private final ShowRepository shows;
    private final ShowSeatRepository showSeats;
    private final SeatHoldRepository holds;
    private final PaymentRepository payments;
    private final UserRepository users;
    private final Pricing pricing;
    private final WaitlistService waitlist;
    private final SeatEvents seatEvents;
    private final Clock clock;
    private final Duration ttl;
    private final TransactionTemplate tx;
    private final Counter expired;

    public HoldService(ShowRepository shows, ShowSeatRepository showSeats, SeatHoldRepository holds,
                       PaymentRepository payments, UserRepository users, Pricing pricing, WaitlistService waitlist,
                       SeatEvents seatEvents,
                       Clock clock, @Value("${app.holds.ttl:PT10M}") Duration ttl,
                       PlatformTransactionManager transactionManager, MeterRegistry meters) {
        this.shows = shows;
        this.showSeats = showSeats;
        this.holds = holds;
        this.payments = payments;
        this.users = users;
        this.pricing = pricing;
        this.waitlist = waitlist;
        this.seatEvents = seatEvents;
        this.clock = clock;
        this.ttl = ttl;
        this.tx = new TransactionTemplate(transactionManager);
        this.expired = Counter.builder("holds.expired").description("Seat holds that lapsed unpaid").register(meters);
    }

    /**
     * Holds the seats for this customer. Choosing seats again for the same show replaces their
     * previous hold (seats they keep stay theirs; the others are freed).
     */
    @Observed(name = "hold.create", contextualName = "hold-seats")
    @Transactional
    public HoldView create(Long userId, Long showId, List<Long> requestedSeatIds) {
        Instant now = clock.instant();
        Set<Long> requested = new LinkedHashSet<>(requestedSeatIds);
        if (requested.size() != requestedSeatIds.size()) {
            throw new BadRequestException("The same seat was selected twice");
        }
        // Shared lock first, so the status read below can't be overtaken by a show cancellation
        shows.lockForShare(showId).orElseThrow(() -> ResourceNotFoundException.of("Show", showId));
        Show show = shows.findDetailed(showId).orElseThrow(() -> ResourceNotFoundException.of("Show", showId));
        if (!show.isBookable(now)) {
            throw new BadRequestException(show.getStatus() == ShowStatus.CANCELLED
                    ? "This show has been cancelled" : "Bookings for this show have closed");
        }

        SeatHold previous = holds.findActiveForUpdate(userId, showId).orElse(null);
        Set<Long> previousSeatIds = previous == null ? Set.of() : new HashSet<>(showSeats.seatIdsOfHold(previous.getId()));
        Set<Long> toLock = new HashSet<>(requested);
        toLock.addAll(previousSeatIds);
        List<ShowSeat> locked = showSeats.lockSeats(showId, toLock); // one statement, id order

        List<ShowSeat> wanted = locked.stream().filter(ss -> requested.contains(ss.getSeat().getId())).toList();
        if (wanted.size() != requested.size()) {
            throw new BadRequestException("Some of the selected seats don't exist in this show");
        }
        List<Long> taken = wanted.stream()
                .filter(ss -> !(ss.getStatus() == ShowSeatStatus.AVAILABLE
                        || (previous != null && ss.getStatus() == ShowSeatStatus.HELD
                        && previous.getId().equals(ss.getHold().getId()))))
                .map(ss -> ss.getSeat().getId()).sorted().toList();
        if (!taken.isEmpty()) {
            throw new SeatsUnavailableException(taken); // rolls back: the previous hold is untouched
        }

        if (previous != null) {
            previous.close(SeatHold.Status.RELEASED, now);
            waitlist.holdEnded(previous, false, now);
            holds.flush(); // the partial unique index allows one ACTIVE hold per customer and show
            List<ShowSeat> dropped = locked.stream()
                    .filter(ss -> previousSeatIds.contains(ss.getSeat().getId()) && !requested.contains(ss.getSeat().getId()))
                    .toList();
            waitlist.reallocate(show, dropped, now);
        }

        Pricing.Quote quote = pricing.quote(showId, wanted);
        SeatHold hold = new SeatHold(show, users.getReferenceById(userId), SeatHold.Source.CUSTOMER, now, now.plus(ttl));
        hold.price(wanted.size(), quote.ticketsPaise(), quote.feePaise());
        holds.save(hold);
        wanted.forEach(ss -> ss.hold(hold));
        seatEvents.seatsChanged(showId, wanted); // broadcast on commit
        log.info("Hold {} created: {} seat(s) of show {} until {}", hold.getId(), wanted.size(), showId, hold.getExpiresAt());
        return view(hold, wanted, quote, null);
    }

    /** The customer backs out: the seats are freed (to the waitlist first). */
    @Transactional
    public void release(Long userId, Long holdId) {
        Instant now = clock.instant();
        SeatHold hold = holds.findByIdForUpdate(holdId)
                .filter(h -> h.isOwnedBy(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Hold not found"));
        if (hold.getStatus() != SeatHold.Status.ACTIVE) {
            throw new ConflictException("This hold has already ended");
        }
        List<ShowSeat> seats = showSeats.lockByHold(holdId);
        hold.close(SeatHold.Status.RELEASED, now);
        waitlist.holdEnded(hold, false, now);
        waitlist.reallocate(hold.getShow(), seats, now);
    }

    @Transactional(readOnly = true)
    public HoldView get(Long userId, Long holdId) {
        SeatHold hold = holds.findDetailed(holdId)
                .filter(h -> h.isOwnedBy(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Hold not found"));
        Payment latest = payments.findByHoldIdOrderByCreatedAtDesc(holdId).stream().findFirst().orElse(null);
        if (hold.getStatus() == SeatHold.Status.CONVERTED && hold.getBooking() != null) {
            List<HeldSeat> seats = hold.getBooking().getSeats().stream()
                    .map(bs -> new HeldSeat(bs.getSeat().getId(), bs.getSeatLabel(), bs.getCategory(), bs.getPricePaise()))
                    .toList();
            return view(hold, seats, latest);
        }
        List<ShowSeat> seats = hold.getStatus() == SeatHold.Status.ACTIVE ? showSeats.findByHold(holdId) : List.of();
        return view(hold, seats, seats.isEmpty() ? null : pricing.quote(hold.getShow().getId(), seats), latest);
    }

    /**
     * Expires lapsed holds. Each hold in its own transaction: locking the seats of several holds in
     * one transaction would lock seats out of id order and could deadlock with customers.
     */
    @Scheduled(fixedDelayString = "${app.holds.expiry-interval:PT10S}", initialDelayString = "${app.holds.expiry-initial-delay:PT20S}")
    public int expireDue() {
        int count = 0;
        while (count < 500) {
            Boolean done = tx.execute(status -> {
                Instant now = clock.instant();
                List<SeatHold> due = holds.lockExpired(now, 1);
                if (due.isEmpty()) {
                    return true;
                }
                expire(due.get(0), now);
                return false;
            });
            if (Boolean.TRUE.equals(done)) {
                break;
            }
            count++;
        }
        if (count > 0) {
            log.info("Expired {} seat hold(s)", count);
        }
        return count;
    }

    private void expire(SeatHold hold, Instant now) {
        List<ShowSeat> seats = showSeats.lockByHold(hold.getId());
        hold.close(SeatHold.Status.EXPIRED, now);
        waitlist.holdEnded(hold, false, now);
        Show show = shows.findById(hold.getShow().getId()).orElseThrow();
        int offered = waitlist.reallocate(show, seats, now);
        expired.increment();
        log.info("Hold {} expired; {} seat(s) freed, {} waitlist offer(s)", hold.getId(), seats.size(), offered);
    }

    // ---------------------------------------------------------------- views

    private HoldView view(SeatHold hold, List<ShowSeat> seats, Pricing.Quote quote, Payment latest) {
        List<HeldSeat> held = seats.stream()
                .sorted(Comparator.comparing((ShowSeat ss) -> ss.getSeat().getRowLabel())
                        .thenComparingInt(ss -> ss.getSeat().getSeatNumber()))
                .map(ss -> new HeldSeat(ss.getSeat().getId(), ss.getSeat().label(), ss.getSeat().getCategory(),
                        quote.priceOf(ss)))
                .toList();
        return view(hold, held, latest);
    }

    private HoldView view(SeatHold hold, List<HeldSeat> seats, Payment latest) {
        Show s = hold.getShow();
        return new HoldView(hold.getId(), hold.getStatus(), hold.getSource(), s.getId(), s.getMovie().getTitle(),
                s.getScreen().getCinema().getName(), s.getScreen().getName(), s.getStartsAt(), s.getFormat(), seats,
                hold.getTicketsPaise(), hold.getConvenienceFeePaise(), hold.totalPaise(), hold.getExpiresAt(),
                PaymentSummary.of(latest), hold.getBooking() == null ? null : hold.getBooking().getReference());
    }
}
