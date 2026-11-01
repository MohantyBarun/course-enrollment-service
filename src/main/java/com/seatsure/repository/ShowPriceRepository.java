package com.seatsure.repository;

import com.seatsure.entity.ShowPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ShowPriceRepository extends JpaRepository<ShowPrice, Long> {

    List<ShowPrice> findByShowId(Long showId);

    @Query("SELECT p FROM ShowPrice p WHERE p.show.id IN :showIds")
    List<ShowPrice> findByShowIds(@Param("showIds") Collection<Long> showIds);
}
