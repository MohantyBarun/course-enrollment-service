package com.seatsure.dto;

import com.seatsure.entity.Certificate;
import com.seatsure.entity.SeatCategory;
import com.seatsure.entity.ShowFormat;
import com.seatsure.entity.ShowStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class AdminDtos {

    private AdminDtos() {
    }

    // ---------------------------------------------------------------- movies

    public record MovieRequest(
            @NotBlank @Size(max = 150) String title,
            @NotBlank @Size(max = 2000) String synopsis,
            @Min(value = 30, message = "Running time must be at least 30 minutes")
            @Max(value = 300, message = "Running time must be at most 300 minutes") int durationMinutes,
            @NotBlank @Size(max = 30) String language,
            @NotNull Certificate certificate,
            @NotEmpty(message = "Add at least one genre") List<@NotBlank @Size(max = 30) String> genres,
            @NotNull LocalDate releaseDate,
            @Size(max = 500) @Pattern(regexp = "^$|^https://.+", message = "Poster URL must start with https://")
            String posterUrl) {
    }

    /** Where customers can find a movie: the rules of the customer home page, from the admin's side. */
    public enum CustomerVisibility {
        /** Has upcoming scheduled shows: listed under "now showing" in {@code showingIn}. */
        NOW_SHOWING,
        /** No upcoming shows, releasing today or later: listed under "coming soon" in every city. */
        COMING_SOON,
        /** Released and no upcoming shows: customers can't find it until a show is scheduled. */
        HIDDEN
    }

    /** A movie in the admin catalogue, with what customers see of it. */
    public record AdminMovie(Long id, String title, String synopsis, String language, Certificate certificate,
                             List<String> genres, int durationMinutes, LocalDate releaseDate, String posterUrl,
                             CustomerVisibility visibility, long upcomingShows, List<String> showingIn) {
    }

    // ---------------------------------------------------------------- cinemas and screens

    public record CinemaRequest(@NotNull Long cityId, @NotBlank @Size(max = 120) String name,
                                @NotBlank @Size(max = 300) String address) {
    }

    /**
     * One row of seats. {@code aisleAfter} lists seat numbers followed by an aisle, e.g. [4, 12]
     * for a 16-seat row split 4 | 8 | 4.
     */
    public record RowSpec(
            @NotBlank @Pattern(regexp = "^[A-Z]{1,2}$", message = "Row labels are 1-2 capital letters") String label,
            @NotNull SeatCategory category,
            @Min(1) @Max(40) int seats,
            List<@Min(1) Integer> aisleAfter) {
    }

    public record ScreenRequest(@NotBlank @Size(max = 40) String name,
                                @NotEmpty @Size(max = 26, message = "At most 26 rows") List<@Valid RowSpec> rows) {
    }

    public record ScreenView(Long id, String name, int seatCount, List<SeatCategory> categories) {
    }

    public record CinemaView(Long id, String name, String address, Long cityId, String cityName,
                             List<ScreenView> screens) {
    }

    // ---------------------------------------------------------------- shows

    public record ShowRequest(@NotNull Long movieId, @NotNull Long screenId,
                              @NotNull @Future(message = "The show must start in the future") Instant startsAt,
                              @NotNull ShowFormat format,
                              @NotEmpty Map<SeatCategory, @Positive Integer> pricesPaise) {
    }

    public record CancelShowRequest(@NotBlank @Size(max = 200) String reason) {
    }

    public record AdminShowView(Long id, Long movieId, String movieTitle, Long screenId, String screenName,
                                Instant startsAt, Instant endsAt, ShowFormat format, ShowStatus status,
                                long totalSeats, long bookedSeats) {
    }

    public record ShowCancelled(Long showId, int bookingsCancelled) {
    }

    public record AdminBookingView(String reference, String customerName, String customerEmail, String movieTitle,
                                   String cinemaName, Instant startsAt, int seats, int totalPaise, String status,
                                   Instant bookedAt) {
    }

    // ---------------------------------------------------------------- dashboard

    public record DayStat(LocalDate date, long bookings, long tickets, long revenuePaise) {
    }

    public record MovieStat(String title, long tickets, long revenuePaise) {
    }

    public record ShowOccupancy(Long showId, String movieTitle, String cinemaName, Instant startsAt, long booked,
                                long total) {
    }

    /** Totals are for today in the audience's time zone; the series covers the last 7 days. */
    public record Dashboard(long bookingsToday, long ticketsToday, long revenueTodayPaise, List<DayStat> last7Days,
                            List<MovieStat> topMovies, List<ShowOccupancy> busiestUpcomingShows, Instant generatedAt) {
    }
}
