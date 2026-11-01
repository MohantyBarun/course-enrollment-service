package com.seatsure.config;
import com.seatsure.filter.JwtAuthFilter;
import com.seatsure.idempotency.IdempotencyFilter;
import com.seatsure.ratelimit.RateLimitFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
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

    private static final String CUSTOMER = "ROLE_CUSTOMER";
    private static final String ADMIN = "ROLE_ADMIN";

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
                        // Sign-up and sign-in. Refresh and logout are authorised by the refresh token
                        // in the body, so they work after the short-lived access token has expired.
                        .requestMatchers("/auth/register", "/auth/login", "/auth/refresh", "/auth/logout",
                                "/auth/demo", "/auth/demo-login").permitAll()
                        // Browsing is public: you only need an account to book
                        .requestMatchers(HttpMethod.GET, "/cities", "/cities/*/home", "/movies/*",
                                "/movies/*/showtimes", "/shows/*", "/shows/*/seats").permitAll()
                        // Called by the payment gateway, authenticated by its HMAC signature instead of a JWT
                        .requestMatchers(HttpMethod.POST, "/webhooks/payments").permitAll()
                        // Checkout and your own bookings: customers only
                        .requestMatchers("/shows/*/holds", "/holds/**", "/mock-gateway/**",
                                "/shows/*/waitlist", "/waitlist/**").hasAuthority(CUSTOMER)
                        .requestMatchers(HttpMethod.GET, "/bookings/me").hasAuthority(CUSTOMER)
                        .requestMatchers(HttpMethod.POST, "/bookings/*/cancel").hasAuthority(CUSTOMER)
                        .requestMatchers("/admin/**").hasAuthority(ADMIN)
                        .anyRequest().authenticated()
                )
                // No or expired token: 401 (the client should refresh), not the default 403
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
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
