package com.seatsure.repository;

import com.seatsure.entity.Movie;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public interface MovieRepository extends JpaRepository<Movie, Long> {

    /** "Now showing" in a city: movies with at least one upcoming scheduled show there. */
    @Query("""
            SELECT DISTINCT m FROM Show s JOIN s.movie m JOIN s.screen sc JOIN sc.cinema c
            WHERE c.city.id = :cityId AND s.status = com.seatsure.entity.ShowStatus.SCHEDULED AND s.startsAt > :now
            """)
    List<Movie> findShowingInCity(@Param("cityId") Long cityId, @Param("now") Instant now);

    /** "Coming soon": not released yet. */
    List<Movie> findByReleaseDateAfterOrderByReleaseDateAsc(LocalDate date);

    List<Movie> findAllByOrderByReleaseDateDesc();
}
