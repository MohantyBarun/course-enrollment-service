package com.seatsure.service;

import com.seatsure.config.CacheConfig;
import com.seatsure.dto.BookingDtos.BookingView;
import com.seatsure.dto.PagedResponseDTO;
import com.seatsure.entity.*;
import com.seatsure.exception.BadRequestException;
import com.seatsure.exception.ConflictException;
import com.seatsure.exception.ResourceNotFoundException;
import com.seatsure.outbox.OutboxEvents;
import com.seatsure.outbox.OutboxService;
import com.seatsure.payment.Refunds;
import com.seatsure.repository.BookingRepository;
import com.seatsure.repository.ShowSeatRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Bookings: created only from a paid hold (see PaymentService), viewed, and cancelled with a
 * refund. Freed seats go to the waitlist first.
 */
@Slf4j
@Service
public class BookingService {

    private static final char[] REFERENCE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ShowSeatRepository showSeats;
    private final BookingRepository bookings;
    private final Pricing pricing;
    private final WaitlistService waitlist;
    private final Refunds refunds;
    private final OutboxService outbox;
    private final Clock clock;
    private final Duration cancellationCutoff;

    public BookingService(ShowSeatRepository showSeats, BookingRepository bookings, Pricing pricing,
                          WaitlistService waitlist, Refunds refunds, OutboxService outbox, Clock clock,
                          @Value("${app.booking.cancellation-cutoff:PT2H}") Duration cancellationCutoff) {
        this.showSeats = showSeats;
        this.bookings = bookings;
        this.pricing = pricing;
        this.waitlist = waitlist;
        this.refunds = refunds;
        this.outbox = outbox;
        this.clock = clock;
        this.cancellationCutoff = cancellationCutoff;
    }

    /**
     * Turns a live, paid hold into a booking. The caller holds the show (shared) and hold locks;
     * the seats are locked here and must all still belong to the hold.
     */
    @CacheEvict(cacheNames = CacheConfig.ADMIN_DASHBOARD, allEntries = true)
    @Transactional(propagation = Propagation.MANDATORY)
    public Booking createFromHold(SeatHold hold, Payment payment, Instant now) {
        List<ShowSeat> seats = showSeats.lockByHold(hold.getId());
        if (seats.size() != hold.getSeatCount()) {
            throw new IllegalStateException("Hold " + hold.getId() + " has " + seats.size() + " seats, expected "
                    + hold.getSeatCount());
        }
        Show show = hold.getShow();
        Pricing.Quote quote = pricing.quote(show.getId(), seats);
        Booking booking = new Booking(newReference(), hold.getUser(), show);
        seats.stream()
                .sorted(Comparator.comparing((ShowSeat ss) -> ss.getSeat().getRowLabel())
                        .thenComparingInt(ss -> ss.getSeat().getSeatNumber()))
                .forEach(ss -> booking.addSeat(ss.getSeat(), quote.priceOf(ss)));
        booking.setConvenienceFeePaise(hold.getConvenienceFeePaise());
        booking.setPayment(payment);
        bookings.save(booking);
        seats.forEach(ss -> ss.book(booking));

        User user = hold.getUser();
        outbox.record("Booking", booking.getId(), OutboxEvents.BOOKING_CONFIRMED, new OutboxEvents.BookingConfirmed(
                booking.getReference(), user.getName(), user.getEmail(), show.getMovie().getTitle(),
                show.getScreen().getCinema().getName(), show.getScreen().getName(), show.getStartsAt(),
                show.getFormat().label(), booking.getSeats().stream().map(BookingSeat::getSeatLabel).toList(),
                booking.totalPaise()));
        log.info("Booking {} confirmed: {} seat(s) for show {}", booking.getReference(), booking.getSeatCount(), show.getId());
        return booking;
    }

    @CacheEvict(cacheNames = CacheConfig.ADMIN_DASHBOARD, allEntries = true)
    @Transactional
    public BookingView cancel(Long userId, String reference, String reason) {
        Instant now = clock.instant();
        // Lock first, so a double click (or a show cancellation at the same moment) can't release seats twice
        Booking locked = bookings.findByReferenceForUpdate(reference).orElseThrow(() -> notFound(reference));
        if (!locked.getUser().getId().equals(userId)) {
            throw notFound(reference); // don't reveal that someone else's booking exists
        }
        if (locked.getStatus() == BookingStatus.CANCELLED) {
            throw new ConflictException("This booking is already cancelled");
        }
        Booking booking = bookings.findDetailed(reference).orElseThrow(() -> notFound(reference));
        if (!isCancellable(booking, now)) {
            throw new BadRequestException("Bookings can be cancelled up to "
                    + cancellationCutoff.toHours() + " hours before the show");
        }
        List<ShowSeat> seats = showSeats.lockByBooking(booking.getId());
        cancelAndRefund(booking, reason == null || reason.isBlank() ? "Cancelled by customer" : reason.trim(), now);
        int offered = waitlist.reallocate(booking.getShow(), seats, now);
        log.info("Booking {} cancelled; {} seat(s) freed, {} waitlist offer(s)", reference, seats.size(), offered);
        return BookingView.of(booking, false);
    }

    /** An admin cancelled the whole show: every confirmed booking is cancelled and refunded. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int cancelAllForShow(Long showId, String reason) {
        Instant now = clock.instant();
        List<Booking> confirmed = bookings.findConfirmedForShowForUpdate(showId);
        for (Booking b : confirmed) {
            showSeats.lockByBooking(b.getId()).forEach(ShowSeat::release);
            cancelAndRefund(b, reason, now);
        }
        return confirmed.size();
    }

    @Transactional(readOnly = true)
    public BookingView get(Long userId, boolean admin, String reference) {
        Booking booking = bookings.findDetailed(reference).orElseThrow(() -> notFound(reference));
        if (!admin && !booking.getUser().getId().equals(userId)) {
            throw notFound(reference);
        }
        return BookingView.of(booking, !admin && isCancellable(booking, clock.instant()));
    }

    @Transactional(readOnly = true)
    public PagedResponseDTO<BookingView> mine(Long userId, int page, int size) {
        Instant now = clock.instant();
        var result = bookings.findForUser(userId, PageRequest.of(page, size))
                .map(b -> BookingView.of(b, isCancellable(b, now)));
        return PagedResponseDTO.from(result);
    }

    boolean isCancellable(Booking booking, Instant now) {
        return booking.getStatus() == BookingStatus.CONFIRMED
                && booking.getShow().getStatus() == ShowStatus.SCHEDULED
                && now.isBefore(booking.getShow().getStartsAt().minus(cancellationCutoff));
    }

    /** Seats must already be locked (and released or handed on) by the caller. */
    private void cancelAndRefund(Booking booking, String reason, Instant now) {
        booking.cancel(reason, now);
        refunds.refund(booking.getPayment(), reason, now);
        outbox.record("Booking", booking.getId(), OutboxEvents.BOOKING_CANCELLED,
                new OutboxEvents.BookingCancelled(booking.getReference(), booking.getUser().getName(),
                        booking.getUser().getEmail(), booking.getShow().getMovie().getTitle(),
                        booking.getShow().getStartsAt(), booking.totalPaise(), reason));
    }

    /** "SS" + 8 characters without look-alikes (0/O, 1/I/L, U): about 6.5e11 combinations. */
    private String newReference() {
        for (int attempt = 0; attempt < 5; attempt++) {
            StringBuilder ref = new StringBuilder("SS");
            for (int i = 0; i < 8; i++) {
                ref.append(REFERENCE_ALPHABET[RANDOM.nextInt(REFERENCE_ALPHABET.length)]);
            }
            if (!bookings.existsByReference(ref.toString())) {
                return ref.toString(); // the unique constraint still guards the tiny race
            }
        }
        throw new IllegalStateException("Could not generate a unique booking reference");
    }

    private static ResourceNotFoundException notFound(String reference) {
        return new ResourceNotFoundException("Booking not found: " + reference);
    }
}
