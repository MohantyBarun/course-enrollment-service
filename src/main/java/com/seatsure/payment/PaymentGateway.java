package com.seatsure.payment;

/**
 * The payment provider, as SeatSure sees it. The outcome of a payment never comes back from these
 * calls: the provider reports it later with a signed webhook ({@code POST /webhooks/payments}).
 * {@link com.seatsure.payment.mock.MockPaymentGateway} is the only implementation; a real provider
 * would be another one.
 */
public interface PaymentGateway {

    /** Registers a payment; returns the path of the page where the customer pays. */
    String createPayment(String gatewayRef, int amountPaise, String description);

    /** Returns the money for a payment that succeeded. */
    void refund(String gatewayRef, int amountPaise, String reason);
}
