package com.seatsure.controller;

import com.seatsure.dto.CheckoutDtos.*;
import com.seatsure.entity.Payment;
import com.seatsure.payment.PaymentService;
import com.seatsure.payment.PaymentWebhookService;
import com.seatsure.payment.WebhookSigner;
import com.seatsure.payment.mock.MockPaymentGateway;
import com.seatsure.service.HoldService;
import com.seatsure.service.WaitlistService;
import com.seatsure.util.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Checkout: hold seats -> start a payment -> pay on the (mock) payment page -> the gateway's webhook
 * confirms the booking. The client polls GET /holds/{id} until bookingReference appears.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Checkout", description = "Seat holds, payments and the waitlist")
public class CheckoutController {

    private final HoldService holds;
    private final PaymentService payments;
    private final MockPaymentGateway mockGateway;
    private final PaymentWebhookService webhooks;
    private final WaitlistService waitlist;

    @Operation(summary = "Hold seats for 10 minutes while you pay",
            description = "All or nothing: if any seat was taken first, nothing is held and the 409 lists the taken "
                    + "seats in unavailableSeatIds. Holding seats again for the same show replaces your previous hold.")
    @PostMapping("/shows/{showId}/holds")
    public ResponseEntity<HoldView> hold(@PathVariable Long showId, @Valid @RequestBody HoldRequest request,
                                         @Parameter(hidden = true) @AuthenticationPrincipal CurrentUser user) {
        HoldView hold = holds.create(user.id(), showId, request.seatIds());
        return ResponseEntity.created(URI.create("/holds/" + hold.holdId())).body(hold);
    }

    @Operation(summary = "A hold, its latest payment, and the booking once paid")
    @GetMapping("/holds/{holdId}")
    public HoldView getHold(@PathVariable Long holdId,
                            @Parameter(hidden = true) @AuthenticationPrincipal CurrentUser user) {
        return holds.get(user.id(), holdId);
    }

    @Operation(summary = "Release a hold (the seats are freed)")
    @DeleteMapping("/holds/{holdId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable Long holdId, @Parameter(hidden = true) @AuthenticationPrincipal CurrentUser user) {
        holds.release(user.id(), holdId);
    }

    @Operation(summary = "Start paying for a hold",
            description = "Returns the payment page to send the customer to. The result arrives by webhook.")
    @PostMapping("/holds/{holdId}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentStarted pay(@PathVariable Long holdId,
                              @Parameter(hidden = true) @AuthenticationPrincipal CurrentUser user) {
        return payments.start(user.id(), holdId);
    }

    @Operation(summary = "Test payment page: approve or decline a payment (mock gateway)",
            description = "Stands in for the bank's page. The gateway then sends a signed webhook to /webhooks/payments.")
    @PostMapping("/mock-gateway/payments/{gatewayRef}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> completeMockPayment(@PathVariable String gatewayRef,
                                                   @Valid @RequestBody MockCheckoutRequest request,
                                                   @Parameter(hidden = true) @AuthenticationPrincipal CurrentUser user) {
        Payment payment = payments.pendingFor(user.id(), gatewayRef);
        mockGateway.complete(gatewayRef, payment.getAmountPaise(), request.outcome() == MockOutcome.SUCCESS);
        return Map.of("status", "submitted");
    }

    @Operation(summary = "Payment webhook (called by the gateway)",
            description = "Signed with HMAC-SHA256 in the SeatSure-Signature header. Redelivered events are acknowledged and skipped.")
    @SecurityRequirements
    @PostMapping("/webhooks/payments")
    public Map<String, String> webhook(@RequestBody String body,
                                       @RequestHeader(value = WebhookSigner.HEADER, required = false) String signature) {
        return Map.of("result", webhooks.handle(body, signature).name());
    }

    @Operation(summary = "Join the waitlist for a sold-out show",
            description = "When seats free up, they're held for the first customer in line whose request fits, "
                    + "and emailed to them with 10 minutes to pay.")
    @PostMapping("/shows/{showId}/waitlist")
    @ResponseStatus(HttpStatus.CREATED)
    public WaitlistView joinWaitlist(@PathVariable Long showId, @Valid @RequestBody WaitlistRequest request,
                                     @Parameter(hidden = true) @AuthenticationPrincipal CurrentUser user) {
        return waitlist.join(user.id(), showId, request.seats());
    }

    @GetMapping("/waitlist/me")
    public List<WaitlistView> myWaitlist(@Parameter(hidden = true) @AuthenticationPrincipal CurrentUser user) {
        return waitlist.mine(user.id());
    }

    @DeleteMapping("/waitlist/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leaveWaitlist(@PathVariable Long entryId,
                              @Parameter(hidden = true) @AuthenticationPrincipal CurrentUser user) {
        waitlist.leave(user.id(), entryId);
    }
}
