package com.seatsure.service;

import com.seatsure.config.CacheConfig;
import com.seatsure.dto.AdminDtos.*;
import com.seatsure.dto.CatalogDtos.MovieDetail;
import com.seatsure.entity.*;
import com.seatsure.exception.BadRequestException;
import com.seatsure.exception.ConflictException;
import com.seatsure.exception.ResourceNotFoundException;
import com.seatsure.repository.*;
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
import java.util.*;
import java.util.stream.Collectors;

/** Admin: movies, cinemas, screen layouts and show scheduling. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminCatalogService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH);

    private final CityRepository cities;
    private final CinemaRepository cinemas;
    private final ScreenRepository screens;
    private final SeatRepository seats;
    private final MovieRepository movies;
    private final ShowRepository shows;
    private final ShowPriceRepository prices;
    private final ShowSeatRepository showSeats;
    private final BookingService bookingService;
    private final SeatHoldRepository holds;
    private final WaitlistService waitlist;
    private final Clock clock;
    private final ZoneId zone;

    // ---------------------------------------------------------------- movies

    @Transactional(readOnly = true)
    public List<MovieDetail> movies() {
        return movies.findAllByOrderByReleaseDateDesc().stream().map(MovieDetail::of).toList();
    }

    @Transactional
    public MovieDetail createMovie(MovieRequest request) {
        Movie movie = new Movie();
        apply(movie, request);
        return MovieDetail.of(movies.save(movie));
    }

    @CacheEvict(cacheNames = CacheConfig.NOW_SHOWING, allEntries = true)
    @Transactional
    public MovieDetail updateMovie(Long id, MovieRequest request) {
        Movie movie = movies.findById(id).orElseThrow(() -> new ResourceNotFoundException("Movie not found: " + id));
        if (movie.getDurationMinutes() != request.durationMinutes()) {
            // Existing shows were scheduled (and checked for overlaps) with the old running time
            throw new BadRequestException("The running time can't change once the movie is created");
        }
        apply(movie, request);
        movie.setUpdatedAt(clock.instant());
        return MovieDetail.of(movie);
    }

    private static void apply(Movie movie, MovieRequest r) {
        movie.setTitle(r.title().trim());
        movie.setSynopsis(r.synopsis().trim());
        movie.setDurationMinutes(r.durationMinutes());
        movie.setLanguage(r.language().trim());
        movie.setCertificate(r.certificate());
        movie.setGenreList(r.genres());
        movie.setReleaseDate(r.releaseDate());
        movie.setPosterUrl(r.posterUrl() == null || r.posterUrl().isBlank() ? null : r.posterUrl().trim());
    }

    // ---------------------------------------------------------------- cinemas and screens

    @Transactional(readOnly = true)
    public List<CinemaView> cinemas() {
        List<Cinema> all = cinemas.findAllWithCity();
        return all.stream().map(this::view).toList();
    }

    @Transactional
    public CinemaView createCinema(CinemaRequest request) {
        City city = cities.findById(request.cityId())
                .orElseThrow(() -> new ResourceNotFoundException("City not found: " + request.cityId()));
        if (cinemas.existsByCityIdAndNameIgnoreCase(city.getId(), request.name().trim())) {
            throw new ConflictException("A cinema with this name already exists in " + city.getName());
        }
        Cinema cinema = cinemas.save(new Cinema(city, request.name().trim(), request.address().trim()));
        return view(cinema);
    }

    /** Creates a screen and its seats from a row-by-row layout. */
    @Transactional
    public ScreenView createScreen(Long cinemaId, ScreenRequest request) {
        Cinema cinema = cinemas.findById(cinemaId)
                .orElseThrow(() -> new ResourceNotFoundException("Cinema not found: " + cinemaId));
        if (screens.existsByCinemaIdAndNameIgnoreCase(cinemaId, request.name().trim())) {
            throw new ConflictException("This cinema already has a screen called " + request.name().trim());
        }
        Set<String> labels = new HashSet<>();
        for (RowSpec row : request.rows()) {
            if (!labels.add(row.label())) {
                throw new BadRequestException("Row " + row.label() + " appears twice");
            }
        }
        Screen screen = screens.save(new Screen(cinema, request.name().trim()));
        List<Seat> layout = layoutSeats(screen, request.rows());
        seats.saveAll(layout);
        log.info("Screen {} created at cinema {} with {} seats", screen.getName(), cinemaId, layout.size());
        return new ScreenView(screen.getId(), screen.getName(), layout.size(),
                layout.stream().map(Seat::getCategory).distinct().sorted().toList());
    }

    /** Seat n of a row sits in grid column n + (number of aisles before it). */
    public static List<Seat> layoutSeats(Screen screen, List<RowSpec> rows) {
        List<Seat> result = new ArrayList<>();
        for (RowSpec row : rows) {
            Set<Integer> aisles = new HashSet<>(row.aisleAfter() == null ? List.of() : row.aisleAfter());
            for (Integer a : aisles) {
                if (a >= row.seats()) {
                    throw new BadRequestException("Row " + row.label() + ": an aisle after seat " + a
                            + " is outside the row (" + row.seats() + " seats)");
                }
            }
            int col = 0;
            for (int n = 1; n <= row.seats(); n++) {
                col++;
                result.add(new Seat(screen, row.label(), n, col, row.category()));
                if (aisles.contains(n)) {
                    col++; // leave an empty column
                }
            }
        }
        return result;
    }

    private CinemaView view(Cinema cinema) {
        List<Screen> cinemaScreens = screens.findByCinemaIdOrderByName(cinema.getId());
        List<ScreenView> screenViews = cinemaScreens.stream().map(sc -> {
            List<Seat> screenSeats = seats.findByScreenIdOrderByRowLabelAscGridColAsc(sc.getId());
            return new ScreenView(sc.getId(), sc.getName(), screenSeats.size(),
                    screenSeats.stream().map(Seat::getCategory).distinct().sorted().toList());
        }).toList();
        return new CinemaView(cinema.getId(), cinema.getName(), cinema.getAddress(), cinema.getCity().getId(),
                cinema.getCity().getName(), screenViews);
    }

    // ---------------------------------------------------------------- shows

    /**
     * Schedules a show. Overlaps with another show on the same screen are rejected by the
     * database's exclusion constraint, which also covers two admins scheduling at the same moment.
     */
    @CacheEvict(cacheNames = CacheConfig.NOW_SHOWING, allEntries = true)
    @Transactional
    public AdminShowView createShow(ShowRequest request) {
        Movie movie = movies.findById(request.movieId())
                .orElseThrow(() -> new ResourceNotFoundException("Movie not found: " + request.movieId()));
        Screen screen = screens.findWithCinema(request.screenId())
                .orElseThrow(() -> new ResourceNotFoundException("Screen not found: " + request.screenId()));
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
        shows.lockForCancellation(showId).orElseThrow(() -> new ResourceNotFoundException("Show not found: " + showId));
        Show show = shows.findDetailed(showId).orElseThrow();
        if (show.getStatus() == ShowStatus.CANCELLED) {
            throw new ConflictException("This show is already cancelled");
        }
        if (!show.getStartsAt().isAfter(clock.instant())) {
            throw new BadRequestException("This show has already started");
        }
        Instant now = clock.instant();
        show.setStatus(ShowStatus.CANCELLED);
        for (SeatHold hold : holds.findActiveForShowForUpdate(showId)) {
            showSeats.lockByHold(hold.getId()).forEach(ShowSeat::release);
            hold.close(SeatHold.Status.RELEASED, now); // a payment still in flight is refunded when it lands
        }
        int cancelled = bookingService.cancelAllForShow(showId, "Show cancelled by the cinema: " + reason.trim());
        int closed = waitlist.closeForShow(showId, now);
        log.info("Show {} cancelled; {} booking(s) cancelled, {} waitlist entr(ies) closed", showId, cancelled, closed);
        return new ShowCancelled(showId, cancelled);
    }
}
