package com.seatsure.payment;

import com.seatsure.entity.Payment;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RefundsTest {

    private final PaymentGateway gateway = mock(PaymentGateway.class);
    private final Refunds refunds = new Refunds(gateway);

    private static Payment succeeded(int amountPaise) {
        Payment payment = new Payment(null, null, "pay_123", amountPaise, Instant.now());
        payment.moveTo(Payment.Status.SUCCEEDED, null, Instant.now());
        return payment;
    }

    @Test
    void refundsTheAmountDue_byDefault() {
        Payment payment = succeeded(50_000);

        assertThat(refunds.refund(payment, "Show cancelled", Instant.now())).isTrue();

        verify(gateway).refund("pay_123", 50_000, "Show cancelled");
        assertThat(payment.getStatus()).isEqualTo(Payment.Status.REFUNDED);
    }

    @Test
    void refundsExactlyWhatWasTaken_whenThatDiffers() {
        refunds.refund(succeeded(50_000), 50_100, "Amount mismatch", Instant.now());

        verify(gateway).refund("pay_123", 50_100, "Amount mismatch");
    }

    @Test
    void onlySucceededPayments_areRefunded_andOnlyOnce() {
        Payment payment = succeeded(50_000);
        refunds.refund(payment, "first", Instant.now());

        assertThat(refunds.refund(payment, "second", Instant.now())).isFalse();
        assertThat(refunds.refund(null, "none", Instant.now())).isFalse();
        verify(gateway, times(1)).refund(anyString(), anyInt(), anyString());
    }
}
