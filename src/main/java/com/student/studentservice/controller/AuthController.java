package com.student.studentservice.controller;

import com.student.studentservice.dto.*;
import com.student.studentservice.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Login, token refresh, logout and password change")
public class AuthController {
    private final AuthService authService;

    @Operation(summary = "Log in",
            description = "Returns an access token (15 min) and a refresh token (7 days). "
                    + "5 wrong passwords block the account for 30 minutes; more than 10 block it permanently.")
    @SecurityRequirements // public endpoint: no JWT needed
    @PostMapping("/login")
    public ResponseEntity<LoginResponseDTO> login(
            @Valid @RequestBody LoginRequestDTO requestDTO) {
        log.info("POST /auth/login called for: {}", requestDTO.getEmail());
        LoginResponseDTO response = authService.userLogin(requestDTO);
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    @Operation(summary = "Get a new access token", description = "Authorised by the refresh token in the body.")
    @SecurityRequirements // public endpoint: no JWT needed
    @PostMapping("/refresh")
    public ResponseEntity<RefreshTokenResponseDTO> refresh(@Valid @RequestBody RefreshTokenRequestDTO request) {
        return ResponseEntity.ok(authService.refreshToken(request));
    }

    @Operation(summary = "Log out", description = "Revokes all of the user's refresh tokens.")
    @SecurityRequirements // public endpoint: no JWT needed
    @PostMapping("/logout")
    public ResponseEntity<String> logout(@Valid @RequestBody RefreshTokenRequestDTO request) {
        return ResponseEntity.ok(authService.logout(request));
    }

    @Operation(summary = "Change your own password",
            description = "Required on first login (with the temporary password as oldPassword). "
                    + "Afterwards, call /auth/refresh to get an unrestricted access token.")
    @PostMapping("/change-password")
    public ResponseEntity<String> changePassword(@Valid @RequestBody ChangePasswordRequestDTO request,
                                                 @Parameter(hidden = true) Authentication authentication) {
        // principal is the email that JwtAuthFilter put in the security context
        String email = authentication.getName();
        log.info("POST /auth/change-password called for: {}", email);
        return ResponseEntity.ok(authService.changePassword(email, request));
    }
}
