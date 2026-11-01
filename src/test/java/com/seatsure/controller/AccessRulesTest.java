package com.seatsure.controller;

import com.seatsure.config.AppConfig;
import com.seatsure.dto.BookingDtos.BookingView;
import com.seatsure.entity.BookingStatus;
import com.seatsure.entity.ShowFormat;
import com.seatsure.service.AdminCatalogService;
import com.seatsure.service.BookingService;
import com.seatsure.service.CatalogService;
import com.seatsure.service.DashboardService;
import com.seatsure.util.JwtUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static com.seatsure.controller.JwtTestTokens.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Who may call what: browsing is public, booking is for customers, /admin is for admins. */
@WebMvcTest({CatalogController.class, BookingController.class, AdminController.class})
@Import({AppConfig.class, JwtUtil.class})
@TestPropertySource(properties = {JWT_PROPERTIES_SECRET, "jwt.access.expiration=900000"})
class AccessRulesTest {

    private static final String BOOK_BODY = "{\"seatIds\":[1,2]}";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;

    @MockitoBean private CatalogService catalog;
    @MockitoBean private BookingService bookings;
    @MockitoBean private AdminCatalogService adminCatalog;
    @MockitoBean private DashboardService dashboard;

    @Test
    void browsing_isPublic() throws Exception {
        mockMvc.perform(get("/cities")).andExpect(status().isOk());
        mockMvc.perform(get("/movies/1/showtimes").param("cityId", "1")).andExpect(status().isOk());
        mockMvc.perform(get("/shows/1/seats")).andExpect(status().isOk());
    }

    @Test
    void showtimes_withoutCity_is400() throws Exception {
        mockMvc.perform(get("/movies/1/showtimes")).andExpect(status().isBadRequest());
    }

    @Test
    void booking_withoutToken_is401() throws Exception {
        mockMvc.perform(post("/shows/1/bookings").contentType(MediaType.APPLICATION_JSON).content(BOOK_BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/bookings/me")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the booking is made for the user in the token")
    void booking_asCustomer_usesTokenUser() throws Exception {
        when(bookings.book(eq(CUSTOMER_ID), eq(1L), anyList())).thenReturn(new BookingView("SS2345678A",
                BookingStatus.CONFIRMED, 1L, 1L, "Movie", null, "Cinema", "Pune", "Audi 1", Instant.now(),
                ShowFormat.TWO_D, List.of(), 20_000, 2_500, 22_500, Instant.now(), null, null, true));

        mockMvc.perform(post("/shows/1/bookings").header("Authorization", customer(jwtUtil))
                        .contentType(MediaType.APPLICATION_JSON).content(BOOK_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/bookings/SS2345678A"));
        verify(bookings).book(eq(CUSTOMER_ID), eq(1L), anyList());
    }

    @Test
    void booking_tooManySeats_is400() throws Exception {
        mockMvc.perform(post("/shows/1/bookings").header("Authorization", customer(jwtUtil))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seatIds\":[1,2,3,4,5,6,7,8,9,10,11]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminRoutes_forbiddenToCustomers_allowedToAdmins() throws Exception {
        mockMvc.perform(get("/admin/dashboard").header("Authorization", customer(jwtUtil)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/dashboard").header("Authorization", admin(jwtUtil)))
                .andExpect(status().isOk());
    }

    @Test
    void customerRoutes_forbiddenToAdmins() throws Exception {
        mockMvc.perform(post("/shows/1/bookings").header("Authorization", admin(jwtUtil))
                        .contentType(MediaType.APPLICATION_JSON).content(BOOK_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidPaging_is400() throws Exception {
        mockMvc.perform(get("/bookings/me").param("size", "500").header("Authorization", customer(jwtUtil)))
                .andExpect(status().isBadRequest());
    }
}
