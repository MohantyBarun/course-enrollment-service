package com.seatsure.demo;

import com.seatsure.dto.AuthDtos.TokenResponse;
import com.seatsure.entity.User;
import com.seatsure.exception.ResourceNotFoundException;
import com.seatsure.repository.UserRepository;
import com.seatsure.service.TokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Passwordless sign-in to the demo accounts, so visitors can explore without signing up. */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication")
public class DemoController {

    public enum DemoRole { ADMIN, CUSTOMER }

    public record DemoLoginRequest(@NotNull(message = "Role is required") DemoRole role) {
    }

    private final DemoDataService demo;
    private final UserRepository users;
    private final TokenService tokens;

    @Operation(summary = "Is demo mode available?")
    @SecurityRequirements
    @GetMapping("/demo")
    public Map<String, Boolean> status() {
        return Map.of("enabled", demo.isEnabled());
    }

    @Operation(summary = "Sign in as the demo admin or demo customer", description = "Only when demo mode is enabled.")
    @SecurityRequirements
    @PostMapping("/demo-login")
    @Transactional
    public TokenResponse demoLogin(@Valid @RequestBody DemoLoginRequest request) {
        if (!demo.isEnabled()) {
            throw new ResourceNotFoundException("Demo mode is not enabled");
        }
        String email = request.role() == DemoRole.ADMIN ? demo.demoAdminEmail() : demo.demoCustomerEmail();
        User user = users.findByEmail(User.normalizeEmail(email))
                .orElseThrow(() -> new ResourceNotFoundException("The demo is being prepared. Try again in a minute."));
        log.info("Demo login as {}", request.role());
        return tokens.issue(user);
    }
}
