package com.seatsure.demo;

import com.seatsure.config.JpaAuditingConfig;
import com.seatsure.config.TimeConfig;
import com.seatsure.entity.LoginSecurity;
import com.seatsure.entity.User;
import com.seatsure.repository.LoginSecurityRepository;
import com.seatsure.repository.UserRepository;
import com.seatsure.service.Roles;
import com.seatsure.support.PostgresTestcontainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the real demo seeding and reset against PostgreSQL (Testcontainers; skipped without Docker). */
@DataJpaTest(properties = "app.demo.enabled=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Import({DemoDataService.class, JpaAuditingConfig.class, TimeConfig.class, DemoDataServiceTest.Config.class,
        PostgresTestcontainer.class})
class DemoDataServiceTest {

    @TestConfiguration
    static class Config {
        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder(4);
        }
    }

    @Autowired private DemoDataService demo;
    @Autowired private UserRepository users;
    @Autowired private LoginSecurityRepository loginSecurity;
    @Autowired private JdbcTemplate jdbc;

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    @Test
    void seed_createsAConsistentDemoDataset() {
        demo.seedIfEmpty();

        assertThat(count("SELECT COUNT(*) FROM cinemas")).isEqualTo(8);
        assertThat(count("SELECT COUNT(*) FROM screens")).isEqualTo(16);
        assertThat(count("SELECT COUNT(*) FROM movies")).isEqualTo(10);
        assertThat(count("SELECT COUNT(*) FROM shows")).isEqualTo(16 * 4 * 4); // 16 screens, 4 slots, 4 days
        assertThat(count("SELECT COUNT(*) FROM show_seats"))
                .as("every show has a full seat inventory")
                .isEqualTo(count("SELECT SUM(n) FROM (SELECT COUNT(*) n FROM shows s JOIN seats st ON st.screen_id = s.screen_id GROUP BY s.id) x"));
        assertThat(count("SELECT COUNT(*) FROM bookings")).isGreaterThan(1000);

        // Inventory and bookings agree, seat for seat
        assertThat(count("SELECT COUNT(*) FROM show_seats WHERE status = 'BOOKED'"))
                .isEqualTo(count("SELECT COUNT(*) FROM booking_seats"))
                .isEqualTo(count("SELECT SUM(seat_count) FROM bookings"));
        assertThat(count("""
                SELECT COUNT(*) FROM show_seats ss JOIN booking_seats bs
                  ON bs.booking_id = ss.booking_id AND bs.seat_id = ss.seat_id
                """)).isEqualTo(count("SELECT COUNT(*) FROM booking_seats"));
        assertThat(count("SELECT COUNT(*) FROM bookings b WHERE tickets_paise <> "
                + "(SELECT SUM(price_paise) FROM booking_seats bs WHERE bs.booking_id = b.id)")).isZero();

        // Both demo accounts exist; the demo customer has bookings to show
        assertThat(users.findByEmail(demo.demoAdminEmail())).get()
                .satisfies(u -> assertThat(u.getRole()).isEqualTo(Roles.ROLE_ADMIN));
        User customer = users.findByEmail(demo.demoCustomerEmail()).orElseThrow();
        assertThat(customer.getRole()).isEqualTo(Roles.ROLE_CUSTOMER);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bookings WHERE user_id = ?", Long.class, customer.getId()))
                .isEqualTo(2);
    }

    @Test
    void seed_isIdempotent() {
        demo.seedIfEmpty();
        demo.seedIfEmpty();

        assertThat(count("SELECT COUNT(*) FROM cinemas")).isEqualTo(8);
        assertThat(count("SELECT COUNT(*) FROM movies")).isEqualTo(10);
    }

    @Test
    void reset_reseedsAndKeepsAdmins() {
        User realAdmin = users.save(new User("Real Admin", "real.admin@seatsure.test", "x", Roles.ROLE_ADMIN));
        loginSecurity.save(new LoginSecurity(realAdmin));
        demo.seedIfEmpty();
        long showsBefore = count("SELECT COUNT(*) FROM shows");

        demo.reset();

        assertThat(count("SELECT COUNT(*) FROM shows")).isEqualTo(showsBefore);
        assertThat(count("SELECT COUNT(*) FROM cinemas")).isEqualTo(8);
        assertThat(users.findByEmail("real.admin@seatsure.test")).isPresent();
        assertThat(users.findByEmail(demo.demoAdminEmail())).isPresent();
        assertThat(users.findByEmail(demo.demoCustomerEmail())).isPresent();
    }
}
