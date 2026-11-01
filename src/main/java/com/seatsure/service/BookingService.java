package com.seatsure.service;

import com.seatsure.config.CacheConfig;
import com.seatsure.dto.BookingDtos.BookingView;
import com.seatsure.dto.PagedResponseDTO;
import com.seatsure.entity.*;
import com.seatsure.exception.BadRequestException;
import com.seatsure.exception.ConflictException;
import com.seatsure.exception.ResourceNotFoundException;
import com.seatsure.exception.SeatsUnavailableException;
import com.seatsure.outbox.OutboxEvents;
import com.seatsure.outbox.OutboxService;
import com.seatsure.repository.*;
import io.micrometer.observation.annotation.Observed;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Booking seats without ever selling one twice.
 *
 * <p>The seats of a show are rows in {@code show_seats}. A booking locks the requested rows
 * ({@code SELECT ... FOR UPDATE}, in id order so overlapping requests can't deadlock), checks
 * they're all still AVAILABLE, and flips them to BOOKED in the same transaction. A competing
 * request for any of the same seats blocks on the lock, then sees them BOOKED and gets a 409 that
 * names the taken seats. Requests for different seats of the same show don't block each other.
 *
 * <p>Lock order: show (shared) -> show seats. Show cancellation takes show (exclusive) -> bookings ->
 * show seats, and a customer cancellation takes booking -> show seats, so there's no cycle.
 */
@Slf4j
@Service
public class BookingService {

    private static final char[] REFERENCE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ShowRepository shows;
    private final ShowSeatRepository showSeats;
    private final ShowPriceRepository prices;
    private final BookingRepository bookings;
    private final UserRepository users;
    private final OutboxService outbox;
    private final Clock clock;
    private final int convenienceFeePerSeatPaise;
    private final Duration cancellationCutoff;

    public BookingService(ShowRepository shows, ShowSeatRepository showSeats, ShowPriceRepository prices,
                          BookingRepository bookings, UserRepository users, OutboxService outbox, Clock clock,
                          @Value("${app.booking.convenience-fee-paise:2500}") int convenienceFeePerSeatPaise,
                          @Value("${app.booking.cancellation-cutoff:PT2H}") Duration cancellationCutoff) {
        this.shows = shows;
        this.showSeats = showSeats;
        this.prices = prices;
        this.bookings = bookings;
        this.users = users;
        this.outbox = outbox;
        this.clock = clock;
        this.convenienceFeePerSeatPaise = convenienceFeePerSeatPaise;
        this.cancellationCutoff = cancellationCutoff;
    }

    @Observed(name = "booking.create", contextualName = "book-seats")
    @CacheEvict(cacheNames = CacheConfig.ADMIN_DASHBOARD, allEntries = true)
    @Transactional
    public BookingView book(Long userId, Long showId, List<Long> requestedSeatIds) {
        Instant now = clock.instant();
        Set<Long> seatIds = new LinkedHashSet<>(requestedSeatIds);
        if (seatIds.size() != requestedSeatIds.size()) {
            throw new BadRequestException("The same seat was selected twice");
        }
        // Shared lock first, so the status read below can't be overtaken by a show cancellation
        shows.lockForShare(showId).orElseThrow(() -> new ResourceNotFoundException("Show not found: " + showId));
        Show show = shows.findDetailed(showId)
                .orElseThrow(() -> new ResourceNotFoundException("Show not found: " + showId));
        if (!show.isBookable(now)) {
            throw new BadRequestException(show.getStatus() == ShowStatus.CANCELLED
                    ? "This show has been cancelled" : "Bookings for this show have closed");
        }

        List<ShowSeat> locked = showSeats.lockSeats(showId, seatIds);
        if (locked.size() != seatIds.size()) {
            throw new BadRequestException("Some of the selected seats don't exist in this show");
        }
        List<Long> taken = locked.stream().filter(ss -> ss.getStatus() != ShowSeatStatus.AVAILABLE)
                .map(ss -> ss.getSeat().getId()).sorted().toList();
        if (!taken.isEmpty()) {
            throw new SeatsUnavailableException(taken);
        }

        Map<SeatCategory, Integer> priceByCategory = prices.findByShowId(showId).stream()
                .collect(Collectors.toMap(ShowPrice::getCategory, ShowPrice::getPricePaise));
        User user = users.getReferenceById(userId);
        Booking booking = new Booking(newReference(), user, show);
        locked.stream()
                .sorted(Comparator.comparing((ShowSeat ss) -> ss.getSeat().getRowLabel())
                        .thenComparingInt(ss -> ss.getSeat().getSeatNumber()))
                .forEach(ss -> {
                    Integer price = priceByCategory.get(ss.getSeat().getCategory());
                    if (price == null) {
                        throw new IllegalStateException("Show " + showId + " has no price for " + ss.getSeat().getCategory());
                    }
                    booking.addSeat(ss.getSeat(), price);
                });
        booking.setConvenienceFeePaise(convenienceFeePerSeatPaise * booking.getSeatCount());
        bookings.save(booking);
        locked.forEach(ss -> ss.book(booking));

        outbox.record("Booking", booking.getId(), OutboxEvents.BOOKING_CONFIRMED, confirmedEvent(booking));
        log.info("Booking {} confirmed: {} seat(s) for show {}", booking.getReference(), booking.getSeatCount(), showId);
        return BookingView.of(booking, isCancellable(booking, now));
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
        cancelAndRelease(booking, reason == null || reason.isBlank() ? "Cancelled by customer" : reason.trim(), now);
        return BookingView.of(booking, false);
    }

    /** Used when an admin cancels a whole show: every confirmed booking is cancelled and refunded. */
    @Transactional
    public int cancelAllForShow(Long showId, String reason) {
        Instant now = clock.instant();
        List<Booking> confirmed = bookings.findConfirmedForShowForUpdate(showId);
        confirmed.forEach(b -> cancelAndRelease(b, reason, now));
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

    private void cancelAndRelease(Booking booking, String reason, Instant now) {
        booking.cancel(reason, now);
        int released = showSeats.releaseBooking(booking.getId());
        outbox.record("Booking", booking.getId(), OutboxEvents.BOOKING_CANCELLED,
                new OutboxEvents.BookingCancelled(booking.getReference(), booking.getUser().getName(),
                        booking.getUser().getEmail(), booking.getShow().getMovie().getTitle(),
                        booking.getShow().getStartsAt(), booking.totalPaise(), reason));
        log.info("Booking {} cancelled ({} seat(s) released): {}", booking.getReference(), released, reason);
    }

    private OutboxEvents.BookingConfirmed confirmedEvent(Booking b) {
        Show s = b.getShow();
        return new OutboxEvents.BookingConfirmed(b.getReference(), b.getUser().getName(), b.getUser().getEmail(),
                s.getMovie().getTitle(), s.getScreen().getCinema().getName(), s.getScreen().getName(),
                s.getStartsAt(), s.getFormat().label(),
                b.getSeats().stream().map(BookingSeat::getSeatLabel).toList(), b.totalPaise());
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
