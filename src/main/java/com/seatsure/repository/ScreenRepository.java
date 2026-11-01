package com.seatsure.repository;

import com.seatsure.entity.Screen;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ScreenRepository extends JpaRepository<Screen, Long> {

    List<Screen> findByCinemaIdOrderByName(Long cinemaId);

    @Query("SELECT s FROM Screen s JOIN FETCH s.cinema c JOIN FETCH c.city WHERE s.id = :id")
    Optional<Screen> findWithCinema(@Param("id") Long id);

    boolean existsByCinemaIdAndNameIgnoreCase(Long cinemaId, String name);
}
