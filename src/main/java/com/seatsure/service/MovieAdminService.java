package com.seatsure.service;

import com.seatsure.config.CacheConfig;
import com.seatsure.dto.AdminDtos.AdminMovie;
import com.seatsure.dto.AdminDtos.CustomerVisibility;
import com.seatsure.dto.AdminDtos.MovieRequest;
import com.seatsure.dto.CatalogDtos.MovieDetail;
import com.seatsure.entity.Movie;
import com.seatsure.exception.BadRequestException;
import com.seatsure.exception.ResourceNotFoundException;
import com.seatsure.repository.MovieRepository;
import com.seatsure.repository.ShowRepository;
import com.seatsure.repository.ShowRepository.UpcomingShowCount;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Admin: the movie catalogue, and where customers can see each movie. */
@Service
@RequiredArgsConstructor
public class MovieAdminService {

    private final MovieRepository movies;
    private final ShowRepository shows;
    private final Clock clock;
    private final ZoneId zone;

    @Transactional(readOnly = true)
    public List<AdminMovie> movies() {
        Map<Long, List<UpcomingShowCount>> upcoming = shows.countUpcomingByMovieAndCity(clock.instant())
                .stream().collect(Collectors.groupingBy(UpcomingShowCount::getMovieId));
        LocalDate today = LocalDate.now(clock.withZone(zone));
        return movies.findAllByOrderByReleaseDateDesc().stream()
                .map(m -> view(m, upcoming.getOrDefault(m.getId(), List.of()), today))
                .toList();
    }

    @Transactional
    public MovieDetail createMovie(MovieRequest request) {
        Movie movie = new Movie();
        movie.update(request.title(), request.synopsis(), request.durationMinutes(), request.language(),
                request.certificate(), request.genres(), request.releaseDate(), request.posterUrl());
        return MovieDetail.of(movies.save(movie));
    }

    @CacheEvict(cacheNames = CacheConfig.NOW_SHOWING, allEntries = true)
    @Transactional
    public MovieDetail updateMovie(Long id, MovieRequest request) {
        Movie movie = movies.findById(id).orElseThrow(() -> ResourceNotFoundException.of("Movie", id));
        if (movie.getDurationMinutes() != request.durationMinutes()) {
            // Existing shows were scheduled (and checked for overlaps) with the old running time
            throw new BadRequestException("The running time can't change once the movie is created");
        }
        movie.update(request.title(), request.synopsis(), request.durationMinutes(), request.language(),
                request.certificate(), request.genres(), request.releaseDate(), request.posterUrl());
        movie.touch(clock.instant());
        return MovieDetail.of(movie);
    }

    /** The customer home page's rules, from the admin's side: shows -> now showing, unreleased -> coming soon. */
    private static AdminMovie view(Movie m, List<UpcomingShowCount> counts, LocalDate today) {
        long showCount = counts.stream().mapToLong(UpcomingShowCount::getShows).sum();
        List<String> cityNames = counts.stream().map(UpcomingShowCount::getCity).sorted().toList();
        CustomerVisibility visibility = showCount > 0 ? CustomerVisibility.NOW_SHOWING
                : !m.getReleaseDate().isBefore(today) ? CustomerVisibility.COMING_SOON
                : CustomerVisibility.HIDDEN;
        return new AdminMovie(m.getId(), m.getTitle(), m.getSynopsis(), m.getLanguage(), m.getCertificate(),
                m.genreList(), m.getDurationMinutes(), m.getReleaseDate(), m.getPosterUrl(),
                visibility, showCount, cityNames);
    }
}
