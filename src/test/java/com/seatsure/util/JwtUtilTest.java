package com.seatsure.util;

import com.seatsure.service.Roles;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtUtilTest {

    static final String SECRET = "test-secret-key-that-is-definitely-longer-than-sixty-four-bytes-for-hs512";

    private final JwtUtil jwtUtil = new JwtUtil(SECRET, 900_000L);

    @Test
    void accessToken_carriesUserIdEmailAndRole() {
        String token = jwtUtil.generateAccessToken(42L, "a@x.com", Roles.ROLE_CUSTOMER);

        CurrentUser user = jwtUtil.parse(token);

        assertThat(user).isEqualTo(new CurrentUser(42L, "a@x.com", Roles.ROLE_CUSTOMER));
        assertThat(user.getName()).isEqualTo("a@x.com");
        assertThat(user.isAdmin()).isFalse();
        assertThat(jwtUtil.accessExpirationSeconds()).isEqualTo(900);
    }

    @Test
    void expiredToken_isRejected() {
        JwtUtil expiring = new JwtUtil(SECRET, -1_000L);
        String token = expiring.generateAccessToken(1L, "a@x.com", Roles.ROLE_CUSTOMER);

        assertThatThrownBy(() -> expiring.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenSignedWithAnotherKey_isRejected() {
        JwtUtil attacker = new JwtUtil("another-secret-key-that-is-also-longer-than-sixty-four-bytes-for-hs512!!", 900_000L);
        String forged = attacker.generateAccessToken(1L, "a@x.com", Roles.ROLE_ADMIN);

        assertThatThrownBy(() -> jwtUtil.parse(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void tamperedPayload_isRejected() {
        String token = jwtUtil.generateAccessToken(1L, "a@x.com", Roles.ROLE_CUSTOMER);
        String[] parts = token.split("\\.");
        // Swap in a payload claiming ROLE_ADMIN, keeping the original signature
        String adminPayload = jwtUtil.generateAccessToken(1L, "a@x.com", Roles.ROLE_ADMIN).split("\\.")[1];

        assertThatThrownBy(() -> jwtUtil.parse(parts[0] + "." + adminPayload + "." + parts[2]))
                .isInstanceOf(JwtException.class);
    }
}
