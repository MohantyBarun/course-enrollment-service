package com.seatsure.service;

import com.seatsure.dto.BookingDtos.BookingView;
import com.seatsure.entity.*;
import com.seatsure.exception.BadRequestException;
import com.seatsure.exception.SeatsUnavailableException;
import com.seatsure.outbox.OutboxEvents;
import com.seatsure.outbox.OutboxService;
import com.seatsure.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Booking rules with the database mocked; the locking itself is covered by BookingConcurrencyIntegrationTest. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BookingServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T06:00:00Z");
    private static final int CLASSIC = 20_000;
    private static final int PRIME = 30_000;

    @Mock private ShowRepository shows;
    @Mock private ShowSeatRepository showSeats;
    @Mock private ShowPriceRepository prices;
    @Mock private BookingRepository bookings;
    @Mock private UserRepository users;
    @Mock private OutboxService outbox;

    private BookingService service;
    private Show show;
    private User user;

    @BeforeEach
    void setUp() {
        service = new BookingService(shows, showSeats, prices, bookings, users, outbox,
                Clock.fixed(NOW, ZoneOffset.UTC), 2500, Duration.ofHours(2));

        City city = new City("Pune", "Maharashtra");
        Cinema cinema = new Cinema(city, "Starlight", "Somewhere");
        Screen screen = new Screen(cinema, "Audi 1");
        Movie movie = new Movie();
        movie.setTitle("Monsoon Protocol");
        movie.setDurationMinutes(150);
        movie.setReleaseDate(LocalDate.of(2026, 10, 1));
        show = new Show(movie, screen, NOW.plus(Duration.ofDays(1)), ShowFormat.TWO_D);
        ReflectionTestUtils.setField(show, "id", 5L);

        user = new User("Asha", "asha@x.com", "hash", Roles.ROLE_CUSTOMER);
        ReflectionTestUtils.setField(user, "id", 7L);

        when(shows.lockForShare(5L)).thenReturn(Optional.of(5L));
        when(shows.findDetailed(5L)).thenReturn(Optional.of(show));
        when(users.getReferenceById(7L)).thenReturn(user);
        when(prices.findByShowId(5L)).thenReturn(List.of(new ShowPrice(show, SeatCategory.CLASSIC, CLASSIC),
                new ShowPrice(show, SeatCategory.PRIME, PRIME)));
        when(bookings.existsByReference(any())).thenReturn(false);
    }

    private ShowSeat showSeat(long seatId, String row, int number, SeatCategory category, ShowSeatStatus status) {
        Seat seat = new Seat(show.getScreen(), row, number, number, category);
        ReflectionTestUtils.setField(seat, "id", seatId);
        ShowSeat ss = new ShowSeat();
        ss.setShow(show);
        ss.setSeat(seat);
        ss.setStatus(status);
        return ss;
    }

    @Test
    @DisplayName("books the seats, prices them per category, adds the fee and records the ticket email")
    void book_success() {
        ShowSeat a = showSeat(11, "H", 2, SeatCategory.PRIME, ShowSeatStatus.AVAILABLE);
        ShowSeat b = showSeat(10, "A", 1, SeatCategory.CLASSIC, ShowSeatStatus.AVAILABLE);
        when(showSeats.lockSeats(eq(5L), anyCollection())).thenReturn(List.of(a, b));

        BookingView view = service.book(7L, 5L, List.of(11L, 10L));

        assertThat(view.seats()).extracting(s -> s.label()).containsExactly("A1", "H2");
        assertThat(view.ticketsPaise()).isEqualTo(CLASSIC + PRIME);
        assertThat(view.convenienceFeePaise()).isEqualTo(2 * 2500);
        assertThat(view.totalPaise()).isEqualTo(CLASSIC + PRIME + 5000);
        assertThat(view.reference()).startsWith("SS").hasSize(10);
        assertThat(view.cancellable()).isTrue();
        assertThat(a.getStatus()).isEqualTo(ShowSeatStatus.BOOKED);
        assertThat(b.getBooking()).isSameAs(a.getBooking()).isNotNull();
        verify(outbox).record(eq("Booking"), any(), eq(OutboxEvents.BOOKING_CONFIRMED), any());
    }

    @Test
    @DisplayName("all or nothing: one taken seat fails the booking and names the taken seat")
    void book_takenSeat_failsWholeBooking() {
        ShowSeat free = showSeat(10, "A", 1, SeatCategory.CLASSIC, ShowSeatStatus.AVAILABLE);
        ShowSeat taken = showSeat(11, "A", 2, SeatCategory.CLASSIC, ShowSeatStatus.BOOKED);
        when(showSeats.lockSeats(eq(5L), anyCollection())).thenReturn(List.of(free, taken));

        assertThatThrownBy(() -> service.book(7L, 5L, List.of(10L, 11L)))
                .isInstanceOfSatisfying(SeatsUnavailableException.class,
                        e -> assertThat(e.getSeatIds()).containsExactly(11L));
        assertThat(free.getStatus()).isEqualTo(ShowSeatStatus.AVAILABLE);
        verify(bookings, never()).save(any());
        verifyNoInteractions(outbox);
    }

    @Test
    void book_seatFromAnotherShow_isRejected() {
        when(showSeats.lockSeats(eq(5L), anyCollection()))
                .thenReturn(List.of(showSeat(10, "A", 1, SeatCategory.CLASSIC, ShowSeatStatus.AVAILABLE)));

        assertThatThrownBy(() -> service.book(7L, 5L, List.of(10L, 999L))).isInstanceOf(BadRequestException.class);
    }

    @Test
    void book_sameSeatTwice_isRejected() {
        assertThatThrownBy(() -> service.book(7L, 5L, List.of(10L, 10L))).isInstanceOf(BadRequestException.class);
        verify(showSeats, never()).lockSeats(any(), any());
    }

    @Test
    void book_afterTheShowStarts_isRejected() {
        show.setStartsAt(NOW.minusSeconds(60));

        assertThatThrownBy(() -> service.book(7L, 5L, List.of(10L)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("closed");
    }

    @Test
    void book_cancelledShow_isRejected() {
        show.setStatus(ShowStatus.CANCELLED);

        assertThatThrownBy(() -> service.book(7L, 5L, List.of(10L)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("cancelled");
    }

    @Test
    @DisplayName("cancellation closes 2 hours before the show")
    void cancellationCutoff() {
        Booking booking = new Booking("SSAAAAAAAA", user, show);
        assertThat(service.isCancellable(booking, NOW)).isTrue();

        show.setStartsAt(NOW.plus(Duration.ofMinutes(119)));
        assertThat(service.isCancellable(booking, NOW)).isFalse();

        show.setStartsAt(NOW.plus(Duration.ofDays(1)));
        booking.cancel("x", NOW);
        assertThat(service.isCancellable(booking, NOW)).isFalse();
    }
}
