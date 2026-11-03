package com.seatsure.service;

import com.seatsure.config.CacheConfig;
import com.seatsure.dto.AdminDtos.*;
import com.seatsure.dto.PageView;
import com.seatsure.entity.Booking;
import com.seatsure.entity.Show;
import com.seatsure.repository.BookingRepository;
import com.seatsure.repository.ShowRepository;
import com.seatsure.repository.ShowSeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

    private final BookingRepository bookings;
    private final ShowRepository shows;
    private final ShowSeatRepository showSeats;
    private final Clock clock;
    private final ZoneId zone;

    /** Up to a minute stale (and evicted on bookings), which is fine for a dashboard. */
    @Cacheable(cacheNames = CacheConfig.ADMIN_DASHBOARD, key = "'summary'")
    public Dashboard dashboard() {
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock.withZone(zone));
        Instant todayStart = today.atStartOfDay(zone).toInstant();
        Instant weekStart = today.minusDays(6).atStartOfDay(zone).toInstant();

        Object[] totals = bookings.totalsSince(todayStart).get(0);

        Map<LocalDate, long[]> perDay = new TreeMap<>();
        for (int i = 6; i >= 0; i--) {
            perDay.put(today.minusDays(i), new long[3]);
        }
        for (Object[] row : bookings.confirmedSince(weekStart)) {
            LocalDate day = ((Instant) row[0]).atZone(zone).toLocalDate();
            long[] acc = perDay.get(day);
            if (acc != null) {
                acc[0]++;
                acc[1] += ((Number) row[1]).longValue();
                acc[2] += ((Number) row[2]).longValue();
            }
        }
        List<DayStat> series = perDay.entrySet().stream()
                .map(e -> new DayStat(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2])).toList();

        List<MovieStat> top = bookings.topMoviesSince(weekStart, PageRequest.of(0, 5)).stream()
                .map(r -> new MovieStat((String) r[0], ((Number) r[1]).longValue(), ((Number) r[2]).longValue()))
                .toList();

        return new Dashboard(number(totals[0]), number(totals[1]), number(totals[2]), series, top,
                busiestUpcoming(now), now);
    }

    /** The fullest scheduled shows in the next 24 hours. */
    List<ShowOccupancy> busiestUpcoming(Instant now) {
        List<Show> upcoming = shows.findScheduledBetween(now, now.plusSeconds(24 * 3600));
        if (upcoming.isEmpty()) {
            return List.of();
        }
        Map<Long, long[]> counts = new HashMap<>();
        showSeats.availability(upcoming.stream().map(Show::getId).toList()).forEach(row -> counts.put((Long) row[0],
                new long[]{((Number) row[1]).longValue(), ((Number) row[2]).longValue()}));
        return upcoming.stream()
                .map(s -> {
                    long[] c = counts.getOrDefault(s.getId(), new long[]{0, 0});
                    return new ShowOccupancy(s.getId(), s.getMovie().getTitle(), s.getScreen().getCinema().getName(),
                            s.getStartsAt(), c[0] - c[1], c[0]);
                })
                .filter(o -> o.total() > 0)
                .sorted(Comparator.comparingDouble((ShowOccupancy o) -> (double) o.booked() / o.total()).reversed())
                .limit(5)
                .toList();
    }

    public PageView<AdminBookingView> recentBookings(int page, int size) {
        return PageView.from(bookings.findRecent(PageRequest.of(page, size)).map(DashboardService::view));
    }

    static AdminBookingView view(Booking b) {
        Show s = b.getShow();
        return new AdminBookingView(b.getReference(), b.getUser().getName(), b.getUser().getEmail(),
                s.getMovie().getTitle(), s.getScreen().getCinema().getName(), s.getStartsAt(), b.getSeatCount(),
                b.totalPaise(), b.getStatus().name(), b.getCreatedAt());
    }

    private static long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }
}
