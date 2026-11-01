package com.student.studentservice.config;

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
                title = "Student Management API",
                version = "1.0",
                description = """
                        REST API for managing students, with JWT authentication.

                        **Try it:** call `POST /auth/login`, copy the `accessToken`, click **Authorize** and paste it.
                        New students must change their temporary password (`POST /auth/change-password`) \
                        and then call `POST /auth/refresh` before other endpoints are available."""
        ),
        security = @SecurityRequirement(name = "bearerAuth")
)
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {
}
