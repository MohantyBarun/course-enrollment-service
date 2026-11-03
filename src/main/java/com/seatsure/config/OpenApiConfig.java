package com.seatsure.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata for Swagger UI (/swagger-ui.html). Endpoints require a JWT by default;
 * use "Authorize" in Swagger UI with the accessToken returned by POST /auth/login.
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "SeatSure API",
                version = "1.0",
                description = """
                        Movie ticket booking: browse what's on, pick seats, book. Seats are never sold twice.
                        Browsing is public. **To book:** call `POST /auth/register` or `POST /auth/login`
                        (or `POST /auth/demo-login` on the demo), copy the `accessToken`, click **Authorize** and paste it."""
        ),
        security = @SecurityRequirement(name = "bearerAuth")
)
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {
}
