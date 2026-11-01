package com.seatsure.controller;

import com.seatsure.service.Roles;
import com.seatsure.util.JwtUtil;

/** Builds real signed tokens for web-layer tests, so the actual JwtAuthFilter is exercised. */
final class JwtTestTokens {

    static final String JWT_PROPERTIES_SECRET =
            "jwt.secret=web-layer-test-secret-that-is-longer-than-sixty-four-bytes-for-hs512-signing";
    static final long CUSTOMER_ID = 7L;
    static final String CUSTOMER_EMAIL = "customer@seatsure.test";

    private JwtTestTokens() {
    }

    static String bearer(String token) {
        return "Bearer " + token;
    }

    static String admin(JwtUtil jwtUtil) {
        return bearer(jwtUtil.generateAccessToken(1L, "admin@seatsure.test", Roles.ROLE_ADMIN));
    }

    static String customer(JwtUtil jwtUtil) {
        return bearer(jwtUtil.generateAccessToken(CUSTOMER_ID, CUSTOMER_EMAIL, Roles.ROLE_CUSTOMER));
    }
}
