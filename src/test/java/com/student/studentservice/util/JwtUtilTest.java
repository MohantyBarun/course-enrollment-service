package com.student.studentservice.util;

import com.student.studentservice.service.Roles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;

class JwtUtilTest {

    static final String SECRET = "test-secret-key-that-is-definitely-longer-than-sixty-four-bytes-for-hs512";

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = newJwtUtil(SECRET, 900_000L);
    }

    static JwtUtil newJwtUtil(String secret, long accessExpirationMs) {
        JwtUtil util = new JwtUtil();
        ReflectionTestUtils.setField(util, "secret", secret);
        ReflectionTestUtils.setField(util, "accessExpiration", accessExpirationMs);
        ReflectionTestUtils.setField(util, "refreshExpiration", 604_800_000L);
        return util;
    }

    @Test
    void accessToken_carriesEmailRoleTypeAndPasswordChangeFlag() {
        String token = jwtUtil.generateAccessToken("a@x.com", Roles.ROLE_ADMIN, true);

        assertThat(jwtUtil.extractEmail(token)).isEqualTo("a@x.com");
        assertThat(jwtUtil.extractRole(token)).isEqualTo("ROLE_ADMIN");
        assertThat(jwtUtil.extractTokenType(token)).isEqualTo("ACCESS");
        assertThat(jwtUtil.isPasswordChangeRequired(token)).isTrue();
        assertThat(jwtUtil.validateToken(token, "a@x.com")).isTrue();
    }

    @Test
    void accessToken_withoutPasswordChangeFlag() {
        String token = jwtUtil.generateAccessToken("a@x.com", Roles.ROLE_STUDENT, false);

        assertThat(jwtUtil.isPasswordChangeRequired(token)).isFalse();
    }

    @Test
    void refreshToken_isTypedRefreshAndHasNoRole() {
        String token = jwtUtil.generateRefreshToken("a@x.com");

        assertThat(jwtUtil.extractTokenType(token)).isEqualTo("REFRESH");
        assertThat(jwtUtil.extractRole(token)).isNull();
    }

    @Test
    @DisplayName("regression: refresh tokens generated in the same second are still unique")
    void refreshTokens_areUniqueEvenWhenIssuedTogether() {
        String first = jwtUtil.generateRefreshToken("a@x.com");
        String second = jwtUtil.generateRefreshToken("a@x.com");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void validateToken_rejectsDifferentEmail() {
        String token = jwtUtil.generateAccessToken("a@x.com", Roles.ROLE_STUDENT, false);

        assertThat(jwtUtil.validateToken(token, "b@x.com")).isFalse();
    }

    @Test
    void validateToken_rejectsExpiredToken() {
        JwtUtil expiring = newJwtUtil(SECRET, -1_000L);
        String token = expiring.generateAccessToken("a@x.com", Roles.ROLE_STUDENT, false);

        assertThat(expiring.validateToken(token, "a@x.com")).isFalse();
    }

    @Test
    void tokenSignedWithAnotherKey_isRejected() {
        JwtUtil attacker = newJwtUtil("another-secret-key-that-is-also-longer-than-sixty-four-bytes-for-hs512!!", 900_000L);
        String forged = attacker.generateAccessToken("a@x.com", Roles.ROLE_ADMIN, false);

        assertThat(jwtUtil.validateToken(forged, "a@x.com")).isFalse();
        assertThatThrownBy(() -> jwtUtil.extractEmail(forged)).isInstanceOf(io.jsonwebtoken.JwtException.class);
    }

    @Test
    void tamperedPayload_isRejected() {
        String token = jwtUtil.generateAccessToken("a@x.com", Roles.ROLE_STUDENT, false);
        String[] parts = token.split("\\.");
        // Swap the payload for one claiming ROLE_ADMIN, keeping the original signature
        String adminPayload = jwtUtil.generateAccessToken("a@x.com", Roles.ROLE_ADMIN, false).split("\\.")[1];
        String tampered = parts[0] + "." + adminPayload + "." + parts[2];

        assertThat(jwtUtil.validateToken(tampered, "a@x.com")).isFalse();
    }
}
