package com.seatsure.service;

import com.seatsure.config.CacheConfig;
import com.seatsure.dto.CatalogDtos.*;
import com.seatsure.entity.City;
import com.seatsure.entity.Movie;
import com.seatsure.entity.Seat;
import com.seatsure.entity.Show;
import com.seatsure.entity.ShowPrice;
import com.seatsure.entity.ShowSeat;
import com.seatsure.entity.ShowSeatStatus;
import com.seatsure.exception.ResourceNotFoundException;
import com.seatsure.repository.CityRepository;
import com.seatsure.repository.MovieRepository;
import com.seatsure.repository.ShowPriceRepository;
import com.seatsure.repository.ShowRepository;
import com.seatsure.repository.ShowSeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/** Read-only browsing: what's on in a city, showtimes, and seat maps. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CatalogService {

    /** At or below this share of seats left, a show is "filling fast". */
    static final double FILLING_FAST_SHARE = 0.25;

    private final CityRepository cities;
    private final MovieRepository movies;
    private final ShowRepository shows;
    private final ShowPriceRepository prices;
    private final ShowSeatRepository showSeats;
    private final Clock clock;
    private final ZoneId zone;

    /** Cities customers can pick: the ones with at least one cinema. */
    public List<CityView> cities() {
        return cities.findWithCinemas().stream().map(CityView::of).toList();
    }

    /** Every city, for admins adding a cinema. */
    public List<CityView> allCities() {
        return cities.findAllByOrderByNameAsc().stream().map(CityView::of).toList();
    }

    public City city(Long cityId) {
        return cities.findById(cityId).orElseThrow(() -> new ResourceNotFoundException("City not found: " + cityId));
    }

    public void requireShow(Long showId) {
        if (!shows.existsById(showId)) {
            throw new ResourceNotFoundException("Show not found: " + showId);
        }
    }

    public Movie movie(Long movieId) {
        return movies.findById(movieId).orElseThrow(() -> new ResourceNotFoundException("Movie not found: " + movieId));
    }

    /**
     * Cached per city: the home page is the hottest read. Evicted when shows or movies change; the
     * cutoff "has an upcoming show" can go stale by up to the TTL, which only affects the last show of a day.
     */
    @Cacheable(cacheNames = CacheConfig.NOW_SHOWING, key = "#cityId")
    public List<MovieCard> nowShowing(Long cityId) {
        city(cityId);
        return movies.findShowingInCity(cityId, clock.instant()).stream()
                .sorted(Comparator.comparing(Movie::getReleaseDate).reversed().thenComparing(Movie::getTitle))
                .map(MovieCard::of)
                .toList();
    }

    public List<MovieCard> comingSoon() {
        return movies.findByReleaseDateAfterOrderByReleaseDateAsc(LocalDate.now(clock.withZone(zone))).stream()
                .map(MovieCard::of).toList();
    }

    public MovieDetail movieDetail(Long movieId) {
        return MovieDetail.of(movie(movieId));
    }

    /**
     * Showtimes of a movie in a city on one day (defaults to the first day that has any).
     * Past shows of today are left out: bookings close when a show starts.
     */
    public Showtimes showtimes(Long movieId, Long cityId, LocalDate requestedDate) {
        Movie movie = movie(movieId);
        city(cityId);
        Instant now = clock.instant();
        List<LocalDate> dates = shows.findUpcomingStartTimes(movieId, cityId, now).stream()
                .map(t -> t.atZone(zone).toLocalDate()).distinct().sorted().toList();
        LocalDate date = requestedDate != null ? requestedDate : dates.isEmpty() ? LocalDate.now(zone) : dates.get(0);

        Instant from = date.atStartOfDay(zone).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(zone).toInstant();
        List<Show> dayShows = shows.findForMovieInCity(movieId, cityId, from.isBefore(now) ? now : from, to);

        Map<Long, long[]> availability = availability(dayShows.stream().map(Show::getId).toList());
        Map<Long, List<ShowPrice>> priceMap = prices.findByShowIds(dayShows.stream().map(Show::getId).toList())
                .stream().collect(Collectors.groupingBy(p -> p.getShow().getId()));

        Map<Long, CinemaShowtimes> byCinema = new LinkedHashMap<>();
        for (Show s : dayShows) {
            var cinema = s.getScreen().getCinema();
            long[] counts = availability.getOrDefault(s.getId(), new long[]{0, 0});
            IntSummaryStatistics priceStats = priceMap.getOrDefault(s.getId(), List.of()).stream()
                    .mapToInt(ShowPrice::getPricePaise).summaryStatistics();
            ShowtimeView view = new ShowtimeView(s.getId(), s.getStartsAt(), s.getFormat(), s.getScreen().getName(),
                    availabilityOf(counts[1], counts[0]), counts[1],
                    priceStats.getCount() == 0 ? 0 : priceStats.getMin(),
                    priceStats.getCount() == 0 ? 0 : priceStats.getMax());
            byCinema.computeIfAbsent(cinema.getId(),
                    id -> new CinemaShowtimes(id, cinema.getName(), cinema.getAddress(), new ArrayList<>()))
                    .shows().add(view);
        }
        return new Showtimes(MovieCard.of(movie), cityId, date, dates, List.copyOf(byCinema.values()));
    }

    public ShowDetail showDetail(Long showId) {
        Show s = shows.findDetailed(showId).orElseThrow(() -> new ResourceNotFoundException("Show not found: " + showId));
        var cinema = s.getScreen().getCinema();
        return new ShowDetail(s.getId(), MovieCard.of(s.getMovie()),
                new CinemaRef(cinema.getId(), cinema.getName(), cinema.getAddress(), cinema.getCity().getName()),
                s.getScreen().getName(), s.getStartsAt(), s.getEndsAt(), s.getFormat(), s.getStatus(),
                s.isBookable(clock.instant()), priceViews(showId));
    }

    /** Not cached: it's the page where people race for seats, so it must be current. */
    public SeatMap seatMap(Long showId) {
        Show show = shows.findById(showId).orElseThrow(() -> new ResourceNotFoundException("Show not found: " + showId));
        List<ShowSeat> seats = showSeats.findSeatMap(showId);
        Map<String, List<ShowSeat>> rows = seats.stream()
                .collect(Collectors.groupingBy(ss -> ss.getSeat().getRowLabel(), TreeMap::new, Collectors.toList()));
        List<SeatRow> rowViews = rows.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(ROW_ORDER))
                .map(e -> {
                    List<ShowSeat> row = e.getValue().stream()
                            .sorted(Comparator.comparingInt(ss -> ss.getSeat().getGridCol())).toList();
                    return new SeatRow(e.getKey(), row.get(0).getSeat().getCategory(), row.stream()
                            .map(ss -> {
                                Seat seat = ss.getSeat();
                                return new SeatView(seat.getId(), seat.getSeatNumber(), seat.getGridCol(), ss.getStatus());
                            }).toList());
                }).toList();
        int maxCol = seats.stream().mapToInt(ss -> ss.getSeat().getGridCol()).max().orElse(0);
        long available = seats.stream().filter(ss -> ss.getStatus() == ShowSeatStatus.AVAILABLE).count();
        return new SeatMap(showId, show.isBookable(clock.instant()), maxCol, available, seats.size(),
                priceViews(showId), rowViews);
    }

    List<PriceView> priceViews(Long showId) {
        return prices.findByShowId(showId).stream()
                .sorted(Comparator.comparing(ShowPrice::getCategory))
                .map(p -> new PriceView(p.getCategory(), p.getPricePaise())).toList();
    }

    /** showId -> [total, available]. */
    Map<Long, long[]> availability(Collection<Long> showIds) {
        if (showIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, long[]> result = new HashMap<>();
        for (Object[] row : showSeats.availability(showIds)) {
            result.put((Long) row[0], new long[]{((Number) row[1]).longValue(), ((Number) row[2]).longValue()});
        }
        return result;
    }

    static Availability availabilityOf(long available, long total) {
        if (available == 0) {
            return Availability.SOLD_OUT;
        }
        return available <= total * FILLING_FAST_SHARE ? Availability.FILLING_FAST : Availability.AVAILABLE;
    }

    /** "A" < "B" < ... < "Z" < "AA": shorter labels first, then alphabetical. */
    static final Comparator<String> ROW_ORDER = Comparator.comparingInt(String::length)
            .thenComparing(Comparator.naturalOrder());
}
