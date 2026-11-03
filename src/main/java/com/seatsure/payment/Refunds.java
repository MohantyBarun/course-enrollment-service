package com.seatsure.payment;

import com.seatsure.entity.Payment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Gives back the money for a payment that succeeded. */
@Slf4j
@Component
@RequiredArgsConstructor
public class Refunds {

    private final PaymentGateway gateway;

    /**
     * Only a SUCCEEDED payment is refunded, and it's marked REFUNDED in the caller's transaction,
     * so calling this twice for the same payment refunds once.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean refund(Payment payment, String reason, Instant now) {
        return payment != null && refund(payment, payment.getAmountPaise(), reason, now);
    }

    /** Refunds a specific amount: what the gateway actually took, when that isn't what was due. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean refund(Payment payment, int amountPaise, String reason, Instant now) {
        if (payment.getStatus() != Payment.Status.SUCCEEDED) {
            return false;
        }
        gateway.refund(payment.getGatewayRef(), amountPaise, reason);
        payment.moveTo(Payment.Status.REFUNDED, reason, now);
        log.info("Payment {} refunded: {}", payment.getGatewayRef(), reason);
        return true;
    }
}
