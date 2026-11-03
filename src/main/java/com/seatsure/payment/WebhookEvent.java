package com.seatsure.payment;

/**
 * The body of a payment webhook.
 *
 * @param id   unique per event; a redelivery carries the same id
 * @param type {@link #PAYMENT_SUCCEEDED} or {@link #PAYMENT_FAILED}
 */
public record WebhookEvent(String id, String type, String gatewayRef, int amountPaise, String failureReason,
                           long created) {

    public static final String PAYMENT_SUCCEEDED = "payment.succeeded";
    public static final String PAYMENT_FAILED = "payment.failed";
}
