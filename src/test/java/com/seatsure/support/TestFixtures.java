package com.seatsure.support;

import com.seatsure.dto.AdminDtos.CinemaRequest;
import com.seatsure.dto.AdminDtos.MovieRequest;
import com.seatsure.dto.AdminDtos.RowSpec;
import com.seatsure.dto.AdminDtos.ScreenRequest;
import com.seatsure.dto.AdminDtos.ShowRequest;
import com.seatsure.dto.AuthDtos.RegisterRequest;
import com.seatsure.dto.AuthDtos.TokenResponse;
import com.seatsure.dto.CheckoutDtos.HoldView;
import com.seatsure.dto.CheckoutDtos.PaymentStarted;
import com.seatsure.payment.PaymentService;
import com.seatsure.service.HoldService;
import org.springframework.jdbc.core.JdbcTemplate;
import com.seatsure.entity.Certificate;
import com.seatsure.entity.Seat;
import com.seatsure.entity.SeatCategory;
import com.seatsure.entity.ShowFormat;
import com.seatsure.repository.CityRepository;
import com.seatsure.repository.SeatRepository;
import com.seatsure.service.MovieAdminService;
import com.seatsure.service.ShowScheduleService;
import com.seatsure.service.VenueAdminService;
import com.seatsure.service.AuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds catalog data and customers through the real services. Integration tests share one
 * database, so everything gets a unique name and its own screen (no clashes between tests).
 */
@TestComponent
public class TestFixtures {

    public static final int CLASSIC_PRICE = 20_000; // ₹200
    public static final String PASSWORD = "Secret#123";

    @Autowired private MovieAdminService movieAdmin;
    @Autowired private VenueAdminService venueAdmin;
    @Autowired private ShowScheduleService showSchedule;
    @Autowired private AuthService auth;
    @Autowired private CityRepository cities;
    @Autowired private SeatRepository seats;
    @Autowired private HoldService holds;
    @Autowired private PaymentService payments;
    @Autowired private JdbcTemplate jdbc;

    public record Screen(Long cinemaId, Long screenId, List<Long> seatIds) {
    }

    public static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public Long movie(int minutes) {
        return movieAdmin.createMovie(new MovieRequest(unique("Movie"), "A test movie.", minutes, "Hindi", Certificate.UA,
                List.of("Drama"), LocalDate.now().minusDays(1), null)).id();
    }

    /** A new cinema in Pune with one screen of {@code rows} x {@code seatsPerRow} Classic seats. */
    public Screen screen(int rows, int seatsPerRow) {
        Long cityId = cities.findByNameIgnoreCase("Pune").orElseThrow().getId();
        Long cinemaId = venueAdmin.createCinema(new CinemaRequest(cityId, unique("Cinema"), "Test address")).id();
        List<RowSpec> layout = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            layout.add(new RowSpec(String.valueOf((char) ('A' + r)), SeatCategory.CLASSIC, seatsPerRow, List.of()));
        }
        Long screenId = venueAdmin.createScreen(cinemaId, new ScreenRequest("Audi 1", layout)).id();
        List<Long> seatIds = seats.findByScreenIdOrderByRowLabelAscGridColAsc(screenId).stream().map(Seat::getId).toList();
        return new Screen(cinemaId, screenId, seatIds);
    }

    /** Two days ahead, on the hour, so it's bookable and cancellable. */
    public static Instant inTwoDays() {
        return Instant.now().plus(Duration.ofDays(2)).truncatedTo(ChronoUnit.HOURS);
    }

    public Long show(Long movieId, Long screenId, Instant startsAt) {
        return showSchedule.createShow(new ShowRequest(movieId, screenId, startsAt, ShowFormat.TWO_D,
                Map.of(SeatCategory.CLASSIC, CLASSIC_PRICE))).id();
    }

    /** A show on a fresh screen; returns the show id and the screen (with its seat ids). */
    public record ShowFixture(Long showId, Screen screen) {
        public List<Long> seatIds() {
            return screen.seatIds();
        }
    }

    public ShowFixture show(int rows, int seatsPerRow) {
        Screen screen = screen(rows, seatsPerRow);
        return new ShowFixture(show(movie(120), screen.screenId(), inTwoDays()), screen);
    }

    public TokenResponse customer() {
        return auth.register(new RegisterRequest("Test Customer", unique("customer") + "@seatsure.test", null, PASSWORD));
    }

    /**
     * Books through the real checkout: hold the seats, start a payment, and process the gateway's
     * "succeeded" outcome (directly, without waiting for the webhook to travel over HTTP).
     */
    public String book(Long userId, Long showId, List<Long> seatIds) {
        HoldView hold = holds.create(userId, showId, seatIds);
        PaymentStarted payment = payments.start(userId, hold.holdId());
        payments.onSucceeded(payment.gatewayRef(), payment.amountPaise());
        return holds.get(userId, hold.holdId()).bookingReference();
    }

    /** Makes a hold look 10 minutes old, so the next expiry sweep picks it up. */
    public void ageOut(Long holdId) {
        jdbc.update("UPDATE seat_holds SET expires_at = now() - interval '1 second' WHERE id = ?", holdId);
    }
}
