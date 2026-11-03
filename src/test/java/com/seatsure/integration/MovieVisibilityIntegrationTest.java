package com.seatsure.integration;

import com.seatsure.dto.AdminDtos.MovieRequest;
import com.seatsure.entity.Certificate;
import com.seatsure.entity.User;
import com.seatsure.repository.CityRepository;
import com.seatsure.repository.UserRepository;
import com.seatsure.service.AdminCatalogService;
import com.seatsure.support.FullAppIntegrationTest;
import com.seatsure.support.TestFixtures;
import com.seatsure.util.JwtUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Where a movie shows up for customers, and what the admin is told about it. A movie released
 * today with no shows yet used to be in neither "now showing" nor "coming soon".
 */
@FullAppIntegrationTest
class MovieVisibilityIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TestFixtures fixtures;
    @Autowired private AdminCatalogService admin;
    @Autowired private CityRepository cities;
    @Autowired private UserRepository users;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private ZoneId zone;

    @Test
    @DisplayName("home: today's release without shows is coming soon; an advance booking is only 'now showing' where it plays")
    void home_listsEachMovieOnce() throws Exception {
        LocalDate today = LocalDate.now(zone);
        Long releasedToday = movie(today);
        Long releasedLastWeek = movie(today.minusDays(7));
        Long advanceBooking = movie(today.plusDays(1)); // releases tomorrow, first show in two days
        fixtures.show(advanceBooking, fixtures.screen(1, 4).screenId(), TestFixtures.inTwoDays()); // the screen is in Pune

        Long pune = cities.findByNameIgnoreCase("Pune").orElseThrow().getId();
        Long mumbai = cities.findByNameIgnoreCase("Mumbai").orElseThrow().getId();

        mockMvc.perform(get("/cities/{id}/home", pune))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nowShowing[*].id", hasItem(advanceBooking.intValue())))
                .andExpect(jsonPath("$.comingSoon[*].id", hasItem(releasedToday.intValue())))
                .andExpect(jsonPath("$.comingSoon[*].id", not(hasItem(advanceBooking.intValue()))))
                .andExpect(jsonPath("$.nowShowing[*].id", not(hasItem(releasedLastWeek.intValue()))))
                .andExpect(jsonPath("$.comingSoon[*].id", not(hasItem(releasedLastWeek.intValue()))));

        mockMvc.perform(get("/cities/{id}/home", mumbai))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nowShowing[*].id", not(hasItem(advanceBooking.intValue()))))
                .andExpect(jsonPath("$.comingSoon[*].id", hasItems(advanceBooking.intValue(), releasedToday.intValue())));
    }

    @Test
    @DisplayName("admin movie list says where customers can see each movie")
    void adminMovies_reportCustomerVisibility() throws Exception {
        LocalDate today = LocalDate.now(zone);
        Long hidden = movie(today.minusDays(7));
        Long comingSoon = movie(today);
        Long showing = movie(today.minusDays(1));
        fixtures.show(showing, fixtures.screen(1, 4).screenId(), TestFixtures.inTwoDays());

        mockMvc.perform(get("/admin/movies").header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == %d)].visibility", hidden).value("HIDDEN"))
                .andExpect(jsonPath("$[?(@.id == %d)].upcomingShows", hidden).value(0))
                .andExpect(jsonPath("$[?(@.id == %d)].visibility", comingSoon).value("COMING_SOON"))
                .andExpect(jsonPath("$[?(@.id == %d)].visibility", showing).value("NOW_SHOWING"))
                .andExpect(jsonPath("$[?(@.id == %d)].upcomingShows", showing).value(1))
                .andExpect(jsonPath("$[?(@.id == %d)].showingIn[*]", showing).value(List.of("Pune")));
    }

    private Long movie(LocalDate releaseDate) {
        return admin.createMovie(new MovieRequest(TestFixtures.unique("Movie"), "A test movie.", 120, "Hindi",
                Certificate.UA, List.of("Drama"), releaseDate, null)).id();
    }

    /** A token for the admin the dev profile seeds (not via /auth/login, which other tests rate-limit). */
    private String adminToken() {
        User user = users.findByEmail("admin@seatsure.dev").orElseThrow();
        return jwtUtil.generateAccessToken(user.getId(), user.getEmail(), user.getRole());
    }
}
