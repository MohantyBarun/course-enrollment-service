package com.student.studentservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Actuator endpoints get their own chain: health and info are public (platform health checks),
 * everything else, e.g. /actuator/prometheus, needs HTTP Basic as user "metrics" with
 * METRICS_PASSWORD, which is what a Prometheus scrape config supports. No password, no access.
 */
@Configuration
public class ActuatorSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain actuatorSecurityFilterChain(HttpSecurity http, PasswordEncoder passwordEncoder,
                                                           @Value("${app.metrics.password:}") String metricsPassword)
            throws Exception {
        http.securityMatcher(EndpointRequest.toAnyEndpoint())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(EndpointRequest.to(HealthEndpoint.class, InfoEndpoint.class)).permitAll();
                    if (metricsPassword.isBlank()) {
                        auth.anyRequest().denyAll();
                    } else {
                        auth.anyRequest().hasRole("METRICS");
                    }
                });
        if (!metricsPassword.isBlank()) {
            http.httpBasic(Customizer.withDefaults())
                    .userDetailsService(new InMemoryUserDetailsManager(User.withUsername("metrics")
                            .password(passwordEncoder.encode(metricsPassword)).roles("METRICS").build()));
        }
        return http.build();
    }
}
