package com.seatsure.controller;

import com.seatsure.dto.CatalogDtos.*;
import com.seatsure.service.CatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** Public browsing: no account needed until you book. */
@RestController
@RequiredArgsConstructor
@Tag(name = "Browse", description = "Cities, movies, showtimes and seat maps (public)")
@SecurityRequirements
public class CatalogController {

    private final CatalogService catalog;

    @Operation(summary = "Cities with cinemas")
    @GetMapping("/cities")
    public List<CityView> cities() {
        return catalog.cities();
    }

    @Operation(summary = "Home page for a city: now showing and coming soon")
    @GetMapping("/cities/{cityId}/home")
    public Home home(@PathVariable Long cityId) {
        return new Home(CityView.of(catalog.city(cityId)), catalog.nowShowing(cityId), catalog.comingSoon());
    }

    @Operation(summary = "Movie details")
    @GetMapping("/movies/{movieId}")
    public MovieDetail movie(@PathVariable Long movieId) {
        return catalog.movieDetail(movieId);
    }

    @Operation(summary = "Showtimes of a movie in a city on a day, grouped by cinema",
            description = "Without a date, the first day with shows is returned. availableDates lists every day with shows.")
    @GetMapping("/movies/{movieId}/showtimes")
    public Showtimes showtimes(@PathVariable Long movieId, @RequestParam Long cityId,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                               LocalDate date) {
        return catalog.showtimes(movieId, cityId, date);
    }

    @Operation(summary = "Show details: movie, cinema, time and prices")
    @GetMapping("/shows/{showId}")
    public ShowDetail show(@PathVariable Long showId) {
        return catalog.showDetail(showId);
    }

    @Operation(summary = "Seat map with each seat's current state")
    @GetMapping("/shows/{showId}/seats")
    public SeatMap seats(@PathVariable Long showId) {
        return catalog.seatMap(showId);
    }
}
