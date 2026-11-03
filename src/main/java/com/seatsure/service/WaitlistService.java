package com.seatsure.service;

import com.seatsure.dto.CheckoutDtos.WaitlistView;
import com.seatsure.entity.*;
import com.seatsure.exception.BadRequestException;
import com.seatsure.exception.ConflictException;
import com.seatsure.exception.ResourceNotFoundException;
import com.seatsure.outbox.OutboxEvents;
import com.seatsure.outbox.OutboxService;
import com.seatsure.realtime.SeatEvents;
import com.seatsure.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * The waitlist for sold-out shows, and what happens to seats that come free.
 *
 * <p>Freed seats (an expired hold, a cancelled booking) are already locked by the transaction that
 * frees them. {@link #reallocate} hands them straight to the oldest waiting customers whose
 * request fits, as a new hold, in that same transaction: the public never sees them free in
 * between, and no further seat locks are taken (so this can't deadlock with customers who are
 * locking seats). Whatever nobody in the queue can use becomes AVAILABLE.
 */
@Slf4j
@Service
public class WaitlistService {

    private final WaitlistRepository waitlist;
    private final ShowRepository shows;
    private final ShowSeatRepository showSeats;
    private final SeatHoldRepository holds;
    private final UserRepository users;
    private final Pricing pricing;
    private final OutboxService outbox;
    private final SeatEvents seatEvents;
    private final Clock clock;
    private final Duration holdTtl;

    public WaitlistService(WaitlistRepository waitlist, ShowRepository shows, ShowSeatRepository showSeats,
                           SeatHoldRepository holds, UserRepository users, Pricing pricing, OutboxService outbox,
                           SeatEvents seatEvents,
                           Clock clock, @Value("${app.holds.ttl:PT10M}") Duration holdTtl) {
        this.waitlist = waitlist;
        this.shows = shows;
        this.showSeats = showSeats;
        this.holds = holds;
        this.users = users;
        this.pricing = pricing;
        this.outbox = outbox;
        this.seatEvents = seatEvents;
        this.clock = clock;
        this.holdTtl = holdTtl;
    }

    /** Joining is for sold-out shows only: if seats are free, book them instead. */
    @Transactional
    public WaitlistView join(Long userId, Long showId, int seatsWanted) {
        Instant now = clock.instant();
        Show show = shows.findDetailed(showId).orElseThrow(() -> ResourceNotFoundException.of("Show", showId));
        if (!show.isBookable(now)) {
            throw new BadRequestException("This show isn't taking bookings");
        }
        if (showSeats.countAvailable(showId) > 0) {
            throw new BadRequestException("Seats are still available for this show. Pick them on the seat map.");
        }
        if (waitlist.isOpenForUser(showId, userId)) {
            throw new ConflictException("You're already on the waitlist for this show");
        }
        WaitlistEntry entry = new WaitlistEntry(show, users.getReferenceById(userId), seatsWanted, now);
        try {
            waitlist.saveAndFlush(entry);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("You're already on the waitlist for this show");
        }
        log.info("User {} joined the waitlist for show {} ({} seat(s))", userId, showId, seatsWanted);
        return view(entry);
    }

    @Transactional
    public void leave(Long userId, Long entryId) {
        WaitlistEntry entry = waitlist.findByIdForUpdate(entryId)
                .filter(e -> e.isOwnedBy(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Waitlist entry not found"));
        if (entry.getStatus() != WaitlistEntry.Status.WAITING) {
            throw new ConflictException(entry.getStatus() == WaitlistEntry.Status.OFFERED
                    ? "Seats are already held for you. Release the hold instead." : "You're no longer on this waitlist");
        }
        entry.moveTo(WaitlistEntry.Status.LEFT, clock.instant());
    }

    @Transactional(readOnly = true)
    public List<WaitlistView> mine(Long userId) {
        return waitlist.findForUser(userId).stream().map(this::view).toList();
    }

    /**
     * Gives freed seats (locked by the caller) to the waitlist, oldest entry first; an entry is
     * skipped if it wants more seats than are left. The rest become AVAILABLE.
     *
     * @return how many waitlist entries were offered seats
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int reallocate(Show show, List<ShowSeat> freedSeats, Instant now) {
        List<ShowSeat> pool = new ArrayList<>(freedSeats);
        int offered = 0;
        if (!pool.isEmpty() && show.isBookable(now)) {
            // SKIP LOCKED: never waits, so freeing seats can't block on a customer leaving the queue
            for (WaitlistEntry entry : waitlist.lockQueue(show.getId())) {
                if (pool.isEmpty()) {
                    break;
                }
                if (entry.getSeatsWanted() > pool.size()
                        // already holding seats for this show (one live hold per customer per show)
                        || holds.existsActive(entry.getUser().getId(), show.getId())) {
                    continue;
                }
                List<ShowSeat> picked = pickTogether(pool, entry.getSeatsWanted());
                pool.removeAll(picked);
                offer(show, entry, picked, now);
                offered++;
            }
        }
        pool.forEach(ShowSeat::release);
        seatEvents.seatsChanged(show.getId(), freedSeats); // HELD for the waitlist, or AVAILABLE again
        return offered;
    }

    private void offer(Show show, WaitlistEntry entry, List<ShowSeat> seats, Instant now) {
        Pricing.Quote quote = pricing.quote(show.getId(), seats);
        SeatHold hold = new SeatHold(show, entry.getUser(), SeatHold.Source.WAITLIST, now, now.plus(holdTtl));
        hold.price(seats.size(), quote.ticketsPaise(), quote.feePaise());
        holds.save(hold);
        seats.forEach(ss -> ss.hold(hold));
        entry.offer(hold, now);

        User user = entry.getUser();
        outbox.record("SeatHold", hold.getId(), OutboxEvents.SEATS_OFFERED, new OutboxEvents.SeatsOffered(
                hold.getId(), user.getName(), user.getEmail(), show.getMovie().getTitle(), show.getStartsAt(),
                seats.stream().map(ss -> ss.getSeat().label()).toList(), hold.totalPaise(), hold.getExpiresAt()));
        log.info("Waitlist entry {} offered {} seat(s) for show {} as hold {}", entry.getId(), seats.size(),
                show.getId(), hold.getId());
    }

    /** Prefers n seats side by side in one row; otherwise the first n in row order. */
    static List<ShowSeat> pickTogether(List<ShowSeat> pool, int n) {
        Comparator<ShowSeat> order = Comparator.comparing((ShowSeat ss) -> ss.getSeat().getRowLabel())
                .thenComparingInt(ss -> ss.getSeat().getSeatNumber());
        List<ShowSeat> sorted = pool.stream().sorted(order).toList();
        for (int i = 0; i + n <= sorted.size(); i++) {
            List<ShowSeat> run = sorted.subList(i, i + n);
            boolean together = true;
            for (int k = 1; k < run.size() && together; k++) {
                Seat prev = run.get(k - 1).getSeat();
                Seat cur = run.get(k).getSeat();
                together = prev.getRowLabel().equals(cur.getRowLabel()) && cur.getSeatNumber() == prev.getSeatNumber() + 1;
            }
            if (together) {
                return List.copyOf(run);
            }
        }
        return List.copyOf(sorted.subList(0, n));
    }

    /** When a hold made for a waitlist entry ends. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void holdEnded(SeatHold hold, boolean paid, Instant now) {
        if (hold.getSource() != SeatHold.Source.WAITLIST) {
            return;
        }
        waitlist.findByHoldForUpdate(hold.getId()).ifPresent(entry ->
                entry.moveTo(paid ? WaitlistEntry.Status.FULFILLED : WaitlistEntry.Status.LAPSED, now));
    }

    /** The show was cancelled (or has started): nobody is waiting for it any more. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int closeForShow(Long showId, Instant now) {
        List<WaitlistEntry> open = waitlist.findOpenForShowForUpdate(showId);
        open.forEach(e -> e.moveTo(WaitlistEntry.Status.CLOSED, now));
        return open.size();
    }

    private WaitlistView view(WaitlistEntry e) {
        Show s = e.getShow();
        Long position = e.getStatus() == WaitlistEntry.Status.WAITING
                ? waitlist.countAhead(s.getId(), e.getCreatedAt()) + 1 : null;
        return new WaitlistView(e.getId(), s.getId(), s.getMovie().getTitle(), s.getScreen().getCinema().getName(),
                s.getStartsAt(), e.getSeatsWanted(), e.getStatus(), position,
                e.getHold() == null ? null : e.getHold().getId(), e.getCreatedAt());
    }
}
