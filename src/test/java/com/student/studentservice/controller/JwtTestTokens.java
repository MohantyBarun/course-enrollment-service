package com.student.studentservice.controller;

import com.student.studentservice.service.Roles;
import com.student.studentservice.util.JwtUtil;

/** Builds real signed tokens for web-layer tests, so the actual JwtAuthFilter is exercised. */
final class JwtTestTokens {

    static final String JWT_PROPERTIES_SECRET =
            "jwt.secret=web-layer-test-secret-that-is-longer-than-sixty-four-bytes-for-hs512-signing";

    static final String ADMIN_EMAIL = "admin@college.com";
    static final String STUDENT_EMAIL = "student@college.com";

    private JwtTestTokens() {
    }

    static String bearer(String token) {
        return "Bearer " + token;
    }

    static String admin(JwtUtil jwtUtil) {
        return bearer(jwtUtil.generateAccessToken(ADMIN_EMAIL, Roles.ROLE_ADMIN, false));
    }

    static String student(JwtUtil jwtUtil) {
        return bearer(jwtUtil.generateAccessToken(STUDENT_EMAIL, Roles.ROLE_STUDENT, false));
    }

    /** Token issued to a student who is still on their temporary password. */
    static String restrictedStudent(JwtUtil jwtUtil) {
        return bearer(jwtUtil.generateAccessToken(STUDENT_EMAIL, Roles.ROLE_STUDENT, true));
    }
}
