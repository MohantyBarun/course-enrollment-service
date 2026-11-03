package com.seatsure.controller;

import com.seatsure.config.AppConfig;
import com.seatsure.dto.CheckoutDtos.HoldView;
import com.seatsure.entity.SeatHold;
import com.seatsure.entity.ShowFormat;
import com.seatsure.payment.PaymentService;
import com.seatsure.realtime.SeatStreams;
import com.seatsure.payment.PaymentWebhookService;
import com.seatsure.payment.mock.MockPaymentGateway;
import com.seatsure.service.HoldService;
import com.seatsure.service.WaitlistService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Who may call what: browsing is public, booking is for customers, /admin is for admins. */
@WebMvcTest({CatalogController.class, BookingController.class, CheckoutController.class, AdminController.class})
@Import({AppConfig.class, JwtUtil.class, com.seatsure.demo.DemoPrivacy.class})
@TestPropertySource(properties = {JWT_PROPERTIES_SECRET, "jwt.access.expiration=900000"})
class AccessRulesTest {

    private static final String HOLD_BODY = "{\"seatIds\":[1,2]}";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;

    @MockitoBean private CatalogService catalog;
    @MockitoBean private BookingService bookings;
    @MockitoBean private AdminCatalogService adminCatalog;
    @MockitoBean private DashboardService dashboard;
    @MockitoBean private HoldService holds;
    @MockitoBean private PaymentService payments;
    @MockitoBean private MockPaymentGateway mockGateway;
    @MockitoBean private PaymentWebhookService webhooks;
    @MockitoBean private WaitlistService waitlist;
    @MockitoBean private SeatStreams seatStreams;

    @Test
    void browsing_isPublic() throws Exception {
        mockMvc.perform(get("/cities")).andExpect(status().isOk());
        mockMvc.perform(get("/movies/1/showtimes").param("cityId", "1")).andExpect(status().isOk());
        mockMvc.perform(get("/shows/1/seats")).andExpect(status().isOk());
        when(seatStreams.subscribe(1L)).thenReturn(new org.springframework.web.servlet.mvc.method.annotation.SseEmitter());
        mockMvc.perform(get("/shows/1/seats/stream")).andExpect(status().isOk());
    }

    @Test
    void showtimes_withoutCity_is400() throws Exception {
        mockMvc.perform(get("/movies/1/showtimes")).andExpect(status().isBadRequest());
    }

    @Test
    void checkout_withoutToken_is401() throws Exception {
        mockMvc.perform(post("/shows/1/holds").contentType(MediaType.APPLICATION_JSON).content(HOLD_BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/bookings/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/waitlist/me")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the hold is made for the user in the token")
    void hold_asCustomer_usesTokenUser() throws Exception {
        when(holds.create(eq(CUSTOMER_ID), eq(1L), anyList())).thenReturn(new HoldView(42L, SeatHold.Status.ACTIVE,
                SeatHold.Source.CUSTOMER, 1L, "Movie", "Cinema", "Audi 1", Instant.now(), ShowFormat.TWO_D, List.of(),
                20_000, 2_500, 22_500, Instant.now(), null, null));

        mockMvc.perform(post("/shows/1/holds").header("Authorization", customer(jwtUtil))
                        .contentType(MediaType.APPLICATION_JSON).content(HOLD_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/holds/42"));
        verify(holds).create(eq(CUSTOMER_ID), eq(1L), anyList());
    }

    @Test
    void hold_tooManySeats_is400() throws Exception {
        mockMvc.perform(post("/shows/1/holds").header("Authorization", customer(jwtUtil))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seatIds\":[1,2,3,4,5,6,7,8,9,10,11]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the webhook needs no JWT (it's authenticated by its signature)")
    void webhook_isReachableWithoutToken() throws Exception {
        when(webhooks.handle(any(), any())).thenReturn(PaymentWebhookService.Result.PROCESSED);

        mockMvc.perform(post("/webhooks/payments").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
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
        mockMvc.perform(post("/shows/1/holds").header("Authorization", admin(jwtUtil))
                        .contentType(MediaType.APPLICATION_JSON).content(HOLD_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidPaging_is400() throws Exception {
        mockMvc.perform(get("/bookings/me").param("size", "500").header("Authorization", customer(jwtUtil)))
                .andExpect(status().isBadRequest());
    }
}
