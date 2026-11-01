package com.seatsure.payment;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignerTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final String JWT = "jwt-secret-that-is-long-enough-for-tests-and-then-some-more-characters";
    private static final String BODY = "{\"id\":\"evt_1\",\"type\":\"payment.succeeded\"}";

    private static WebhookSigner at(Instant now, String secret) {
        return new WebhookSigner(secret, JWT, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void signedBody_verifies() {
        WebhookSigner signer = at(NOW, "");
        String header = signer.sign(BODY);

        assertThat(header).startsWith("t=" + NOW.getEpochSecond() + ",v1=");
        assertThat(signer.verify(header, BODY)).isTrue();
    }

    @Test
    void alteredBody_isRejected() {
        WebhookSigner signer = at(NOW, "");
        assertThat(signer.verify(signer.sign(BODY), BODY.replace("succeeded", "failed"))).isFalse();
    }

    @Test
    void differentSecret_isRejected() {
        String header = at(NOW, "secret-a").sign(BODY);
        assertThat(at(NOW, "secret-b").verify(header, BODY)).isFalse();
    }

    @Test
    void replayedOldSignature_isRejected() {
        String header = at(NOW, "").sign(BODY);
        assertThat(at(NOW.plus(Duration.ofMinutes(4)), "").verify(header, BODY)).isTrue();
        assertThat(at(NOW.plus(Duration.ofMinutes(6)), "").verify(header, BODY)).isFalse();
    }

    @Test
    void malformedHeaders_areRejected() {
        WebhookSigner signer = at(NOW, "");
        assertThat(signer.verify(null, BODY)).isFalse();
        assertThat(signer.verify("garbage", BODY)).isFalse();
        assertThat(signer.verify("t=abc,v1=00", BODY)).isFalse();
        assertThat(signer.verify("v1=00", BODY)).isFalse();
    }
}
