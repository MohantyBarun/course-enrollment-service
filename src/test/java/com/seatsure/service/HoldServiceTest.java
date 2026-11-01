package com.seatsure.service;

import com.seatsure.dto.CheckoutDtos.HoldView;
import com.seatsure.entity.*;
import com.seatsure.exception.BadRequestException;
import com.seatsure.exception.SeatsUnavailableException;
import com.seatsure.realtime.SeatEvents;
import com.seatsure.repository.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

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

/** Hold rules with the database mocked; the locking itself is covered by HoldConcurrencyIntegrationTest. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HoldServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T06:00:00Z");
    private static final int CLASSIC = 20_000;
    private static final int PRIME = 30_000;

    @Mock private ShowRepository shows;
    @Mock private ShowSeatRepository showSeats;
    @Mock private ShowPriceRepository prices;
    @Mock private SeatHoldRepository holds;
    @Mock private PaymentRepository payments;
    @Mock private UserRepository users;
    @Mock private WaitlistService waitlist;
    @Mock private SeatEvents seatEvents;
    @Mock private PlatformTransactionManager transactionManager;

    private HoldService service;
    private Show show;

    @BeforeEach
    void setUp() {
        service = new HoldService(shows, showSeats, holds, payments, users, new Pricing(prices, 2500), waitlist, seatEvents,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(10), transactionManager, new SimpleMeterRegistry());

        Screen screen = new Screen(new Cinema(new City("Pune", "Maharashtra"), "Starlight", "Somewhere"), "Audi 1");
        Movie movie = new Movie();
        movie.setTitle("Monsoon Protocol");
        movie.setDurationMinutes(150);
        movie.setReleaseDate(LocalDate.of(2026, 10, 1));
        show = new Show(movie, screen, NOW.plus(Duration.ofDays(1)), ShowFormat.TWO_D);
        ReflectionTestUtils.setField(show, "id", 5L);
        User user = new User("Asha", "asha@x.com", "hash", Roles.ROLE_CUSTOMER);
        ReflectionTestUtils.setField(user, "id", 7L);

        when(shows.lockForShare(5L)).thenReturn(Optional.of(5L));
        when(shows.findDetailed(5L)).thenReturn(Optional.of(show));
        when(users.getReferenceById(7L)).thenReturn(user);
        when(holds.findActiveForUpdate(7L, 5L)).thenReturn(Optional.empty());
        when(prices.findByShowId(5L)).thenReturn(List.of(new ShowPrice(show, SeatCategory.CLASSIC, CLASSIC),
                new ShowPrice(show, SeatCategory.PRIME, PRIME)));
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
    @DisplayName("holds the seats for 10 minutes, priced per category plus the per-seat fee")
    void create_success() {
        ShowSeat a = showSeat(11, "H", 2, SeatCategory.PRIME, ShowSeatStatus.AVAILABLE);
        ShowSeat b = showSeat(10, "A", 1, SeatCategory.CLASSIC, ShowSeatStatus.AVAILABLE);
        when(showSeats.lockSeats(eq(5L), anyCollection())).thenReturn(List.of(b, a));

        HoldView view = service.create(7L, 5L, List.of(11L, 10L));

        assertThat(view.seats()).extracting(s -> s.label()).containsExactly("A1", "H2");
        assertThat(view.ticketsPaise()).isEqualTo(CLASSIC + PRIME);
        assertThat(view.convenienceFeePaise()).isEqualTo(2 * 2500);
        assertThat(view.totalPaise()).isEqualTo(CLASSIC + PRIME + 5000);
        assertThat(view.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        assertThat(a.getStatus()).isEqualTo(ShowSeatStatus.HELD);
        assertThat(a.getHold()).isSameAs(b.getHold()).isNotNull();
        verify(holds).save(any(SeatHold.class));
        verify(seatEvents).seatsChanged(eq(5L), argThat(seats -> seats.size() == 2));
    }

    @Test
    @DisplayName("all or nothing: one taken seat fails the hold and names the taken seat")
    void create_takenSeat_failsWholeHold() {
        ShowSeat free = showSeat(10, "A", 1, SeatCategory.CLASSIC, ShowSeatStatus.AVAILABLE);
        ShowSeat taken = showSeat(11, "A", 2, SeatCategory.CLASSIC, ShowSeatStatus.BOOKED);
        when(showSeats.lockSeats(eq(5L), anyCollection())).thenReturn(List.of(free, taken));

        assertThatThrownBy(() -> service.create(7L, 5L, List.of(10L, 11L)))
                .isInstanceOfSatisfying(SeatsUnavailableException.class,
                        e -> assertThat(e.getSeatIds()).containsExactly(11L));
        assertThat(free.getStatus()).isEqualTo(ShowSeatStatus.AVAILABLE);
        verify(holds, never()).save(any());
        verifyNoInteractions(seatEvents);
    }

    @Test
    void create_seatFromAnotherShow_isRejected() {
        when(showSeats.lockSeats(eq(5L), anyCollection()))
                .thenReturn(List.of(showSeat(10, "A", 1, SeatCategory.CLASSIC, ShowSeatStatus.AVAILABLE)));

        assertThatThrownBy(() -> service.create(7L, 5L, List.of(10L, 999L))).isInstanceOf(BadRequestException.class);
    }

    @Test
    void create_sameSeatTwice_isRejectedBeforeLocking() {
        assertThatThrownBy(() -> service.create(7L, 5L, List.of(10L, 10L))).isInstanceOf(BadRequestException.class);
        verify(showSeats, never()).lockSeats(any(), any());
    }

    @Test
    void create_afterTheShowStarts_isRejected() {
        show.setStartsAt(NOW.minusSeconds(60));

        assertThatThrownBy(() -> service.create(7L, 5L, List.of(10L)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("closed");
    }

    @Test
    void create_cancelledShow_isRejected() {
        show.setStatus(ShowStatus.CANCELLED);

        assertThatThrownBy(() -> service.create(7L, 5L, List.of(10L)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("cancelled");
    }

    @Test
    void holdIsLiveOnlyWhileActiveAndUnexpired() {
        SeatHold hold = new SeatHold(show, null, SeatHold.Source.CUSTOMER, NOW, NOW.plus(Duration.ofMinutes(10)));

        assertThat(hold.isLive(NOW.plus(Duration.ofMinutes(9)))).isTrue();
        assertThat(hold.isLive(NOW.plus(Duration.ofMinutes(10)))).isFalse();
        hold.close(SeatHold.Status.RELEASED, NOW);
        assertThat(hold.isLive(NOW)).isFalse();
    }

    @Test
    @DisplayName("waitlist offers prefer seats side by side")
    void pickTogether_prefersAdjacentSeats() {
        ShowSeat a1 = showSeat(1, "A", 1, SeatCategory.CLASSIC, ShowSeatStatus.HELD);
        ShowSeat a3 = showSeat(3, "A", 3, SeatCategory.CLASSIC, ShowSeatStatus.HELD);
        ShowSeat b4 = showSeat(4, "B", 4, SeatCategory.CLASSIC, ShowSeatStatus.HELD);
        ShowSeat b5 = showSeat(5, "B", 5, SeatCategory.CLASSIC, ShowSeatStatus.HELD);

        assertThat(WaitlistService.pickTogether(List.of(a1, a3, b5, b4), 2)).containsExactly(b4, b5);
        assertThat(WaitlistService.pickTogether(List.of(a1, a3, b5), 2)).as("no pair: first two in order")
                .containsExactly(a1, a3);
    }
}
