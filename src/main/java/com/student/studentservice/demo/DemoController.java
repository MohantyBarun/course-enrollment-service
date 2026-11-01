package com.student.studentservice.demo;

import com.student.studentservice.dto.LoginResponseDTO;
import com.student.studentservice.entity.RefreshToken;
import com.student.studentservice.entity.User;
import com.student.studentservice.exception.ResourceNotFoundException;
import com.student.studentservice.repository.RefreshTokenRepository;
import com.student.studentservice.repository.UserRepository;
import com.student.studentservice.util.JwtUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/** Passwordless sign-in to the demo accounts, so visitors can explore without credentials. */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication")
public class DemoController {

    public enum DemoRole { ADMIN, STUDENT }

    public record DemoLoginRequest(@NotNull(message = "Role is required") DemoRole role) {
    }

    private final DemoDataService demo;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtUtil jwtUtil;

    @Operation(summary = "Is demo mode available?")
    @SecurityRequirements
    @GetMapping("/demo")
    public Map<String, Boolean> status() {
        return Map.of("enabled", demo.isEnabled());
    }

    @Operation(summary = "Sign in as the demo admin or demo student",
            description = "Only available when demo mode is enabled.")
    @SecurityRequirements
    @PostMapping("/demo-login")
    @Transactional
    public LoginResponseDTO demoLogin(@Valid @RequestBody DemoLoginRequest request) {
        if (!demo.isEnabled()) {
            throw new ResourceNotFoundException("Demo mode is not enabled");
        }
        String email = request.role() == DemoRole.ADMIN ? demo.demoAdminEmail() : demo.demoStudentEmail();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("The demo account is being prepared. Try again shortly."));

        String accessToken = jwtUtil.generateAccessToken(user.getEmail(), user.getRole(), false);
        String refreshTokenValue = jwtUtil.generateRefreshToken(user.getEmail());
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setToken(refreshTokenValue);
        refreshToken.setUser(user);
        refreshToken.setExpiryDate(LocalDateTime.now().plusDays(7));
        refreshToken.setIsRevoked(false);
        refreshTokenRepository.save(refreshToken);
        log.info("Demo login as {}", request.role());

        LoginResponseDTO response = new LoginResponseDTO();
        response.setAccessToken(accessToken);
        response.setRefreshToken(refreshTokenValue);
        response.setTokenType("Bearer");
        response.setRole(user.getRole());
        response.setEmail(user.getEmail());
        response.setIsFirstTimeLogin(false);
        return response;
    }
}
