package com.seatsure.repository;

import com.seatsure.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByScreenIdOrderByRowLabelAscGridColAsc(Long screenId);

    @Query("SELECT s.screen.id, COUNT(s) FROM Seat s WHERE s.screen.id IN :screenIds GROUP BY s.screen.id")
    List<Object[]> countByScreen(@Param("screenIds") List<Long> screenIds);
}
