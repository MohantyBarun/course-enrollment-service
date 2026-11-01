package com.seatsure.repository;

import com.seatsure.entity.Cinema;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CinemaRepository extends JpaRepository<Cinema, Long> {

    @Query("SELECT c FROM Cinema c JOIN FETCH c.city WHERE c.city.id = :cityId ORDER BY c.name")
    List<Cinema> findByCity(@Param("cityId") Long cityId);

    @Query("SELECT c FROM Cinema c JOIN FETCH c.city ORDER BY c.city.name, c.name")
    List<Cinema> findAllWithCity();

    boolean existsByCityIdAndNameIgnoreCase(Long cityId, String name);
}
