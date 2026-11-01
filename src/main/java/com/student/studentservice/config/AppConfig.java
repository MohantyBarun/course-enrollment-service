package com.student.studentservice.config;
import com.student.studentservice.filter.JwtAuthFilter;
import com.student.studentservice.idempotency.IdempotencyFilter;
import com.student.studentservice.ratelimit.RateLimitFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@RequiredArgsConstructor
public class AppConfig {

    private static final String STUDENT = "ROLE_STUDENT";
    private static final String[] ADMINS = {"ROLE_ADMIN", "ROLE_SUPER_ADMIN"};

    private final JwtAuthFilter jwtAuthFilter;
    // Optional so web-layer tests (@WebMvcTest) can load this config without their dependencies
    private final ObjectProvider<RateLimitFilter> rateLimitFilter;
    private final ObjectProvider<IdempotencyFilter> idempotencyFilter;

    // Comma-separated; supports exact origins and patterns such as https://*.vercel.app
    @Value("${app.cors.allowed-origins:http://localhost:5173}")
    private String allowedOrigins;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // stateless JWT in headers, no cookies -> CSRF not applicable
                .cors(Customizer.withDefaults()) // uses the corsConfigurationSource bean below
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS) // no sessions, JWT only
                )
                .authorizeHttpRequests(auth -> auth
                        // Public: API docs and the health check used by the hosting platform
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**",
                                "/actuator/health").permitAll()

                        // Refresh and logout are authorised by the refresh token in the body,
                        // so they must work after the short-lived access token has expired.
                        .requestMatchers("/auth/login", "/auth/refresh", "/auth/logout",
                                "/auth/demo", "/auth/demo-login").permitAll()

                        .requestMatchers("/auth/change-password").authenticated()

                        // Student self-service (must come before the admin rules for the same paths)
                        .requestMatchers(HttpMethod.GET, "/students/me", "/students/me/**").hasAuthority(STUDENT)
                        .requestMatchers("/courses/*/enroll").hasAuthority(STUDENT)
                        .requestMatchers("/dashboard/student").hasAuthority(STUDENT)

                        // Readable by every signed-in user
                        .requestMatchers(HttpMethod.GET, "/courses", "/courses/*", "/announcements").authenticated()

                        // Everything else under these paths is administration
                        .requestMatchers("/students", "/students/**", "/courses", "/courses/**", "/enrollments/**",
                                "/announcements", "/announcements/**", "/dashboard/admin", "/admin/**").hasAnyAuthority(ADMINS)

                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        // Order: CORS -> rate limit (per IP, before any auth work) -> JWT -> idempotency (needs the user)
        rateLimitFilter.ifAvailable(f -> http.addFilterBefore(f, JwtAuthFilter.class));
        idempotencyFilter.ifAvailable(f -> http.addFilterAfter(f, JwtAuthFilter.class));

        return http.build();
    }

    /** Lets the separately-hosted frontend (e.g. on Vercel) call this API from the browser. */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type",
                IdempotencyFilter.HEADER, "X-Request-Id"));
        config.setExposedHeaders(List.of("Location", IdempotencyFilter.REPLAYED_HEADER, "X-Request-Id",
                "Retry-After", "X-RateLimit-Limit", "X-RateLimit-Remaining"));
        config.setMaxAge(3600L); // cache preflight responses for an hour

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
