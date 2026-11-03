package com.seatsure.controller;

import com.seatsure.dto.AdminDtos.*;
import com.seatsure.dto.CatalogDtos.CityView;
import com.seatsure.dto.CatalogDtos.MovieDetail;
import com.seatsure.dto.PagedResponseDTO;
import com.seatsure.service.AdminCatalogService;
import com.seatsure.service.CatalogService;
import com.seatsure.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@Validated
@Tag(name = "Admin", description = "Movies, cinemas, screens, shows and sales (admin only)")
public class AdminController {

    private final AdminCatalogService catalog;
    private final CatalogService catalogService;
    private final DashboardService dashboard;

    @Operation(summary = "Sales dashboard")
    @GetMapping("/dashboard")
    public Dashboard dashboard() {
        return dashboard.dashboard();
    }

    @Operation(summary = "Latest bookings across all cinemas")
    @GetMapping("/bookings")
    public PagedResponseDTO<AdminBookingView> bookings(@RequestParam(defaultValue = "0") @Min(0) int page,
                                                       @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return dashboard.recentBookings(page, size);
    }

    @Operation(summary = "Every movie, newest release first, with where customers can see it")
    @GetMapping("/movies")
    public List<AdminMovie> movies() {
        return catalog.movies();
    }

    @PostMapping("/movies")
    @ResponseStatus(HttpStatus.CREATED)
    public MovieDetail createMovie(@Valid @RequestBody MovieRequest request) {
        return catalog.createMovie(request);
    }

    @Operation(summary = "Update a movie", description = "The running time can't change, since shows are scheduled around it.")
    @PutMapping("/movies/{id}")
    public MovieDetail updateMovie(@PathVariable Long id, @Valid @RequestBody MovieRequest request) {
        return catalog.updateMovie(id, request);
    }

    @Operation(summary = "Every city, including those without cinemas yet")
    @GetMapping("/cities")
    public List<CityView> cities() {
        return catalogService.allCities();
    }

    @GetMapping("/cinemas")
    public List<CinemaView> cinemas() {
        return catalog.cinemas();
    }

    @PostMapping("/cinemas")
    @ResponseStatus(HttpStatus.CREATED)
    public CinemaView createCinema(@Valid @RequestBody CinemaRequest request) {
        return catalog.createCinema(request);
    }

    @Operation(summary = "Add a screen with its seat layout",
            description = "Rows are listed front (nearest the screen) to back. aisleAfter: seat numbers followed by an aisle.")
    @PostMapping("/cinemas/{cinemaId}/screens")
    @ResponseStatus(HttpStatus.CREATED)
    public ScreenView createScreen(@PathVariable Long cinemaId, @Valid @RequestBody ScreenRequest request) {
        return catalog.createScreen(cinemaId, request);
    }

    @Operation(summary = "Shows at a cinema on a day, with seats booked")
    @GetMapping("/cinemas/{cinemaId}/shows")
    public List<AdminShowView> shows(@PathVariable Long cinemaId,
                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return catalog.showsAt(cinemaId, date);
    }

    @Operation(summary = "Schedule a show",
            description = "409 if the screen already has a show overlapping this one (running time + 15 minutes to clean).")
    @PostMapping("/shows")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminShowView createShow(@Valid @RequestBody ShowRequest request) {
        return catalog.createShow(request);
    }

    @Operation(summary = "Cancel a show", description = "Cancels every booking for it; each customer is emailed.")
    @PostMapping("/shows/{showId}/cancel")
    public ShowCancelled cancelShow(@PathVariable Long showId, @Valid @RequestBody CancelShowRequest request) {
        return catalog.cancelShow(showId, request.reason());
    }
}
