package com.seatsure.notification;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EmailSendersTest {

    private static EmailMessage to(String address) {
        return new EmailMessage(address, "Subject", "Body");
    }

    @Test
    void allowlist_sendsOnlyToApprovedAddressesAndDomains() {
        EmailSender smtp = mock(EmailSender.class);
        when(smtp.send(any())).thenReturn(EmailSender.Delivery.sent("ok"));
        when(smtp.mode()).thenReturn("SMTP");
        AllowlistEmailSender sender = new AllowlistEmailSender(smtp, " Me@Gmail.com , @college.edu ,");

        assertThat(sender.send(to("me@gmail.com")).sent()).isTrue();
        assertThat(sender.send(to("dean@college.edu")).sent()).isTrue();
        assertThat(sender.send(to("someone@else.com")).sent()).isFalse();
        assertThat(sender.send(to("x@notcollege.edu.evil.com")).sent()).isFalse();
        verify(smtp, times(2)).send(any());
        assertThat(sender.mode()).isEqualTo("SMTP for 2 allowlisted recipients; others logged only");
    }

    @Test
    void allowlist_mustNotBeEmpty() {
        assertThatThrownBy(() -> new AllowlistEmailSender(mock(EmailSender.class), " , "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loggingSender_reportsWhyItDidntSend() {
        LoggingEmailSender sender = new LoggingEmailSender("demo mode");

        EmailSender.Delivery delivery = sender.send(to("a@b.com"));

        assertThat(delivery.sent()).isFalse();
        assertThat(delivery.detail()).isEqualTo("Not sent: demo mode");
        assertThat(sender.mode()).isEqualTo("Logged only (demo mode)");
    }

    @Test
    void smtp_opensTheCircuitAfterRepeatedFailures() {
        JavaMailSender mail = mock(JavaMailSender.class);
        doThrow(new MailSendException("connection refused")).when(mail).send(any(SimpleMailMessage.class));
        CircuitBreaker breaker = CircuitBreaker.of("smtp-test", CircuitBreakerConfig.custom()
                .slidingWindowSize(4).minimumNumberOfCalls(4).failureRateThreshold(50).build());
        SmtpEmailSender sender = new SmtpEmailSender(mail, "from@x.com", breaker);

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> sender.send(to("a@b.com"))).isInstanceOf(MailSendException.class);
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> sender.send(to("a@b.com"))).isInstanceOf(CallNotPermittedException.class);
        verify(mail, times(4)).send(any(SimpleMailMessage.class));
    }
}
