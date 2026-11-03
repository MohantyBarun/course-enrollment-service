package com.seatsure.service;

import com.seatsure.dto.AdminDtos.RowSpec;
import com.seatsure.dto.CatalogDtos.Availability;
import com.seatsure.entity.Seat;
import com.seatsure.entity.SeatCategory;
import com.seatsure.exception.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure rules behind the catalog: seat layouts, availability badges and row order. */
class CatalogRulesTest {

    @Test
    @DisplayName("aisles become empty grid columns; seat numbers stay consecutive")
    void layout_insertsAisleColumns() {
        List<Seat> seats = VenueAdminService.layoutSeats(null,
                List.of(new RowSpec("A", SeatCategory.CLASSIC, 8, List.of(2, 6))));

        assertThat(seats).extracting(Seat::getSeatNumber).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(seats).extracting(Seat::getGridCol).containsExactly(1, 2, 4, 5, 6, 7, 9, 10);
        assertThat(seats.get(0).label()).isEqualTo("A1");
    }

    @Test
    void layout_rowsKeepTheirCategory() {
        List<Seat> seats = VenueAdminService.layoutSeats(null, List.of(
                new RowSpec("A", SeatCategory.CLASSIC, 2, null),
                new RowSpec("B", SeatCategory.RECLINER, 2, List.of())));

        assertThat(seats).extracting(Seat::getCategory)
                .containsExactly(SeatCategory.CLASSIC, SeatCategory.CLASSIC, SeatCategory.RECLINER, SeatCategory.RECLINER);
    }

    @Test
    void layout_aisleOutsideTheRow_isRejected() {
        assertThatThrownBy(() -> VenueAdminService.layoutSeats(null,
                List.of(new RowSpec("A", SeatCategory.CLASSIC, 6, List.of(6)))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void availabilityBadges() {
        assertThat(CatalogService.availabilityOf(0, 100)).isEqualTo(Availability.SOLD_OUT);
        assertThat(CatalogService.availabilityOf(25, 100)).isEqualTo(Availability.FILLING_FAST);
        assertThat(CatalogService.availabilityOf(26, 100)).isEqualTo(Availability.AVAILABLE);
    }

    @Test
    void rowsSortAsCinemasLabelThem() {
        assertThat(Stream.of("AA", "B", "A", "Z").sorted(CatalogService.ROW_ORDER).toList())
                .containsExactly("A", "B", "Z", "AA");
    }
}
