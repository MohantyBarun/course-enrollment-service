package com.seatsure.controller;

import com.seatsure.dto.BookingDtos.BookingView;
import com.seatsure.dto.BookingDtos.CancelRequest;
import com.seatsure.dto.PageView;
import com.seatsure.service.BookingService;
import com.seatsure.util.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;


@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "Bookings", description = "View and cancel your bookings (bookings are made through checkout)")
public class BookingController {

    private final BookingService bookings;

    @Operation(summary = "Your bookings, latest show first")
    @GetMapping("/bookings/me")
    public PageView<BookingView> mine(@RequestParam(defaultValue = "0") @Min(0) int page,
                                              @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size,
                                              @Parameter(hidden = true) @AuthenticationPrincipal CurrentUser user) {
        return bookings.mine(user.id(), page, size);
    }

    @Operation(summary = "One booking (yours, or any booking for an admin)")
    @GetMapping("/bookings/{reference}")
    public BookingView get(@PathVariable String reference,
                           @Parameter(hidden = true) @AuthenticationPrincipal CurrentUser user) {
        return bookings.get(user.id(), user.isAdmin(), reference);
    }

    @Operation(summary = "Cancel your booking", description = "Allowed up to 2 hours before the show; the seats are released.")
    @PostMapping("/bookings/{reference}/cancel")
    public BookingView cancel(@PathVariable String reference, @Valid @RequestBody(required = false) CancelRequest request,
                              @Parameter(hidden = true) @AuthenticationPrincipal CurrentUser user) {
        return bookings.cancel(user.id(), reference, request == null ? null : request.reason());
    }
}
