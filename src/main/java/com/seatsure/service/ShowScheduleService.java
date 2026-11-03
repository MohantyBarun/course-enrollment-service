package com.seatsure.service;

import com.seatsure.config.CacheConfig;
import com.seatsure.dto.AdminDtos.AdminShowView;
import com.seatsure.dto.AdminDtos.ShowCancelled;
import com.seatsure.dto.AdminDtos.ShowRequest;
import com.seatsure.entity.Movie;
import com.seatsure.entity.Screen;
import com.seatsure.entity.Seat;
import com.seatsure.entity.SeatCategory;
import com.seatsure.entity.SeatHold;
import com.seatsure.entity.Show;
import com.seatsure.entity.ShowPrice;
import com.seatsure.entity.ShowSeat;
import com.seatsure.entity.ShowStatus;
import com.seatsure.exception.BadRequestException;
import com.seatsure.exception.ConflictException;
import com.seatsure.exception.ResourceNotFoundException;
import com.seatsure.realtime.SeatEvents;
import com.seatsure.repository.MovieRepository;
import com.seatsure.repository.ScreenRepository;
import com.seatsure.repository.SeatHoldRepository;
import com.seatsure.repository.SeatRepository;
import com.seatsure.repository.ShowPriceRepository;
import com.seatsure.repository.ShowRepository;
import com.seatsure.repository.ShowSeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Admin: scheduling shows (with their prices and seat inventory) and cancelling them. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShowScheduleService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH);

    private final MovieRepository movies;
    private final ScreenRepository screens;
    private final SeatRepository seats;
    private final ShowRepository shows;
    private final ShowPriceRepository prices;
    private final ShowSeatRepository showSeats;
    private final SeatHoldRepository holds;
    private final BookingService bookingService;
    private final WaitlistService waitlist;
    private final SeatEvents seatEvents;
    private final Clock clock;
    private final ZoneId zone;

    /**
     * Schedules a show. Overlaps with another show on the same screen are rejected by the
     * database's exclusion constraint, which also covers two admins scheduling at the same moment.
     */
    @CacheEvict(cacheNames = CacheConfig.NOW_SHOWING, allEntries = true)
    @Transactional
    public AdminShowView createShow(ShowRequest request) {
        Movie movie = movies.findById(request.movieId())
                .orElseThrow(() -> ResourceNotFoundException.of("Movie", request.movieId()));
        Screen screen = screens.findWithCinema(request.screenId())
                .orElseThrow(() -> ResourceNotFoundException.of("Screen", request.screenId()));
        if (!request.startsAt().isAfter(clock.instant())) {
            throw new BadRequestException("The show must start in the future");
        }
        LocalDate showDate = request.startsAt().atZone(zone).toLocalDate();
        if (showDate.isBefore(movie.getReleaseDate())) {
            throw new BadRequestException(movie.getTitle() + " releases on " + movie.getReleaseDate());
        }
        Set<SeatCategory> needed = seats.findByScreenIdOrderByRowLabelAscGridColAsc(screen.getId()).stream()
                .map(Seat::getCategory).collect(Collectors.toCollection(() -> EnumSet.noneOf(SeatCategory.class)));
        if (needed.isEmpty()) {
            throw new BadRequestException("This screen has no seats yet");
        }
        Set<SeatCategory> missing = EnumSet.copyOf(needed);
        missing.removeAll(request.pricesPaise().keySet());
        if (!missing.isEmpty()) {
            throw new BadRequestException("Set a price for: " + missing);
        }

        Show show = new Show(movie, screen, request.startsAt(), request.format());
        try {
            shows.saveAndFlush(show);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException(screen.getName() + " already has a show between "
                    + TIME.format(show.getStartsAt().atZone(zone)) + " and "
                    + TIME.format(show.getEndsAt().atZone(zone)) + " (including 15 minutes to clean up)");
        }
        needed.forEach(c -> prices.save(new ShowPrice(show, c, request.pricesPaise().get(c))));
        int created = showSeats.createInventory(show.getId(), screen.getId());
        log.info("Show {} scheduled: {} on {} at {} ({} seats)", show.getId(), movie.getTitle(), screen.getName(),
                show.getStartsAt(), created);
        return new AdminShowView(show.getId(), movie.getId(), movie.getTitle(), screen.getId(), screen.getName(),
                show.getStartsAt(), show.getEndsAt(), show.getFormat(), show.getStatus(), created, 0);
    }

    /** Shows at a cinema on a day (audience time zone), with how full each one is. */
    @Transactional(readOnly = true)
    public List<AdminShowView> showsAt(Long cinemaId, LocalDate date) {
        Instant from = date.atStartOfDay(zone).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(zone).toInstant();
        List<Show> list = shows.findForCinema(cinemaId, from, to);
        Map<Long, long[]> counts = new HashMap<>();
        if (!list.isEmpty()) {
            showSeats.availability(list.stream().map(Show::getId).toList()).forEach(row -> counts.put((Long) row[0],
                    new long[]{((Number) row[1]).longValue(), ((Number) row[2]).longValue()}));
        }
        return list.stream().map(s -> {
            long[] c = counts.getOrDefault(s.getId(), new long[]{0, 0});
            return new AdminShowView(s.getId(), s.getMovie().getId(), s.getMovie().getTitle(), s.getScreen().getId(),
                    s.getScreen().getName(), s.getStartsAt(), s.getEndsAt(), s.getFormat(), s.getStatus(), c[0],
                    c[0] - c[1]);
        }).toList();
    }

    /**
     * Cancels a show: live holds are released, every booking is cancelled and refunded (each customer
     * is emailed), and the waitlist is closed. Lock order: show -> holds -> seats -> bookings -> seats
     * -> waitlist entries, the same order the rest of checkout uses.
     */
    @CacheEvict(cacheNames = {CacheConfig.NOW_SHOWING, CacheConfig.ADMIN_DASHBOARD}, allEntries = true)
    @Transactional
    public ShowCancelled cancelShow(Long showId, String reason) {
        // Waits for holds and payments in flight (they hold a shared lock) and blocks new ones
        shows.lockForCancellation(showId).orElseThrow(() -> ResourceNotFoundException.of("Show", showId));
        Show show = shows.findDetailed(showId).orElseThrow();
        if (show.getStatus() == ShowStatus.CANCELLED) {
            throw new ConflictException("This show is already cancelled");
        }
        if (!show.getStartsAt().isAfter(clock.instant())) {
            throw new BadRequestException("This show has already started");
        }
        Instant now = clock.instant();
        show.cancel();
        for (SeatHold hold : holds.findActiveForShowForUpdate(showId)) {
            showSeats.lockByHold(hold.getId()).forEach(ShowSeat::release);
            hold.close(SeatHold.Status.RELEASED, now); // a payment still in flight is refunded when it lands
        }
        int cancelled = bookingService.cancelAllForShow(showId, "Show cancelled by the cinema: " + reason.trim());
        int closed = waitlist.closeForShow(showId, now);
        seatEvents.reload(showId); // every open seat map refetches (and sees the show is off)
        log.info("Show {} cancelled; {} booking(s) cancelled, {} waitlist entr(ies) closed", showId, cancelled, closed);
        return new ShowCancelled(showId, cancelled);
    }
}
