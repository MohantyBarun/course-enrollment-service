package com.seatsure.config;

import com.seatsure.entity.LoginSecurity;
import com.seatsure.entity.User;
import com.seatsure.repository.LoginSecurityRepository;
import com.seatsure.repository.UserRepository;
import com.seatsure.service.Roles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the initial admin account on startup so a fresh database is usable.
 * Credentials come from app.admin.email / app.admin.password (ADMIN_EMAIL / ADMIN_PASSWORD).
 * The dev profile supplies local defaults; in prod nothing is created unless both are set.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    private final UserRepository users;
    private final LoginSecurityRepository loginSecurity;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.email:}")
    private String adminEmail;

    @Value("${app.admin.password:}")
    private String adminPassword;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (adminEmail.isBlank() || adminPassword.isBlank()) {
            log.info("ADMIN_EMAIL/ADMIN_PASSWORD not set; skipping admin seeding");
            return;
        }
        if (users.existsByEmail(User.normalizeEmail(adminEmail))) {
            return;
        }
        User admin = users.save(new User("SeatSure Admin", adminEmail, passwordEncoder.encode(adminPassword),
                Roles.ROLE_ADMIN));
        loginSecurity.save(new LoginSecurity(admin));
        log.info("Initial admin created: {}", admin.getEmail());
    }
}
