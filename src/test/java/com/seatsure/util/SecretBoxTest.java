package com.seatsure.util;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretBoxTest {

    private static final String JWT_SECRET = "unit-test-secret-unit-test-secret-unit-test-secret";

    private final SecretBox box = new SecretBox("", JWT_SECRET);

    @Test
    void roundTrips() {
        assertThat(box.decrypt(box.encrypt("Xk9#mP2q"))).isEqualTo("Xk9#mP2q");
    }

    @Test
    void usesAFreshIvEveryTime() {
        assertThat(box.encrypt("same")).isNotEqualTo(box.encrypt("same"));
    }

    @Test
    void tamperedCiphertext_isRejected() {
        byte[] data = Base64.getDecoder().decode(box.encrypt("secret"));
        data[data.length - 1] ^= 1;
        String tampered = Base64.getEncoder().encodeToString(data);

        assertThatThrownBy(() -> box.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anotherKey_cannotDecrypt() {
        SecretBox other = new SecretBox("", JWT_SECRET + "-different");

        assertThatThrownBy(() -> other.decrypt(box.encrypt("secret"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void derivedKey_isNotTheJwtSecretItself() {
        byte[] derived = SecretBox.resolveKey("", JWT_SECRET);

        assertThat(derived).hasSize(32).isNotEqualTo(JWT_SECRET.substring(0, 32).getBytes());
    }

    @Test
    void configuredKey_mustBe32Bytes() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new SecretBox(shortKey, JWT_SECRET)).isInstanceOf(IllegalStateException.class);
        assertThat(new SecretBox(Base64.getEncoder().encodeToString(new byte[32]), JWT_SECRET).encrypt("x")).isNotBlank();
    }
}
