package com.seatsure.repository;

import com.seatsure.entity.Seat;
import com.seatsure.entity.SeatCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByScreenIdOrderByRowLabelAscGridColAsc(Long screenId);

    /** Seats per screen and category, for many screens in one query (the admin's cinema list). */
    @Query("""
            SELECT s.screen.id AS screenId, s.category AS category, COUNT(s) AS seats
            FROM Seat s WHERE s.screen.id IN :screenIds GROUP BY s.screen.id, s.category
            """)
    List<SeatCount> countByScreenAndCategory(@Param("screenIds") List<Long> screenIds);

    interface SeatCount {
        Long getScreenId();

        SeatCategory getCategory();

        long getSeats();
    }
}
