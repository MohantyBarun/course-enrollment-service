package com.student.studentservice.config;

import com.student.studentservice.entity.*;
import com.student.studentservice.repository.*;
import com.student.studentservice.service.AccountStatus;
import com.student.studentservice.service.AdminLevel;
import com.student.studentservice.service.Roles;
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

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PersonsRepository personsRepository;
    private final AdminsRepository adminsRepository;
    private final LoginSecurityRepository loginSecurityRepository;

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
        if (personsRepository.existsByEmail(adminEmail)) {
            return;
        }

        Persons persons = new Persons();
        persons.setName("Super Admin");
        persons.setEmail(adminEmail);
        Persons savedPerson = personsRepository.save(persons);

        Admins admin = new Admins();
        admin.setDepartment("Admin");
        admin.setAdminLevel(AdminLevel.SUPER_ADMIN);
        admin.setPersons(savedPerson);
        adminsRepository.save(admin);

        User adminUser = new User();
        adminUser.setEmail(adminEmail);
        adminUser.setPassword(passwordEncoder.encode(adminPassword));
        adminUser.setRole(Roles.ROLE_ADMIN);
        adminUser.setIsActive(true);
        adminUser.setIsFirstTimeLogin(false);
        adminUser.setTempPassword(null);
        adminUser.setTempPasswordExpiry(null);
        adminUser.setTempPasswordAttemptsCount(0);
        adminUser.setIsEmailVerified(true);
        adminUser.setPersons(savedPerson);
        userRepository.save(adminUser);

        LoginSecurity loginSecurity = new LoginSecurity();
        loginSecurity.setAccountStatus(AccountStatus.ACTIVE);
        loginSecurity.setWrongPasswordCount(0);
        loginSecurity.setPermanentBlock(false);
        loginSecurity.setUser(adminUser);
        loginSecurityRepository.save(loginSecurity);

        log.info("Initial admin created: {}", adminEmail);
    }
}
