package com.seatsure.controller;

import com.seatsure.dto.AuthDtos.ChangePasswordRequest;
import com.seatsure.dto.AuthDtos.LoginRequest;
import com.seatsure.dto.AuthDtos.RefreshRequest;
import com.seatsure.dto.AuthDtos.RegisterRequest;
import com.seatsure.dto.AuthDtos.TokenResponse;
import com.seatsure.dto.AuthDtos.UserView;
import com.seatsure.service.AuthService;
import com.seatsure.service.TokenService;
import com.seatsure.util.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Sign up, log in, token refresh, logout and password change")
public class AuthController {

    private final AuthService authService;
    private final TokenService tokenService;

    @Operation(summary = "Create a customer account", description = "Signs the new customer in straight away.")
    @SecurityRequirements
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @Operation(summary = "Log in",
            description = "Returns an access token (15 min) and a refresh token (7 days). "
                    + "5 wrong passwords block the account for 30 minutes; each further one doubles the block, up to a day.")
    @SecurityRequirements
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @Operation(summary = "Exchange a refresh token for a new token pair",
            description = "The refresh token rotates: the old one stops working. Reusing an old one ends the session.")
    @SecurityRequirements
    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return tokenService.rotate(request.refreshToken());
    }

    @Operation(summary = "Log out of this session")
    @SecurityRequirements
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest request) {
        tokenService.revokeSession(request.refreshToken());
    }

    @Operation(summary = "The signed-in user")
    @GetMapping("/me")
    public UserView me(@Parameter(hidden = true) @AuthenticationPrincipal CurrentUser user) {
        return authService.me(user.id());
    }

    @Operation(summary = "Change your password", description = "Signs out all your other sessions.")
    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest request,
                               @Parameter(hidden = true) @AuthenticationPrincipal CurrentUser user) {
        authService.changePassword(user.id(), request);
    }
}
