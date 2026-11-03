package com.seatsure.repository;

import com.seatsure.entity.City;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CityRepository extends JpaRepository<City, Long> {

    List<City> findAllByOrderByNameAsc();

    @Query("SELECT c FROM City c WHERE EXISTS (SELECT 1 FROM Cinema ci WHERE ci.city = c) ORDER BY c.name")
    List<City> findWithCinemas();

    Optional<City> findByNameIgnoreCase(String name);
}
