package com.seatsure.support;

import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.annotation.*;

/**
 * The whole application against a real PostgreSQL (Testcontainers). Classes using this share one
 * Spring context (and one container). Skipped when Docker isn't available; CI always runs them.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(properties = {
        "jwt.secret=integration-test-secret-that-is-longer-than-sixty-four-bytes-for-hs512-signing",
        "app.rate-limit.login-per-minute=3",
        "app.metrics.password=test-metrics-password",
        "app.outbox.poll-interval=PT1S",
        "spring.datasource.hikari.maximum-pool-size=20"
})
@AutoConfigureMockMvc
@AutoConfigureObservability // a real Prometheus registry instead of the test no-op
@Testcontainers(disabledWithoutDocker = true)
@Import({PostgresTestcontainer.class, TestFixtures.class})
public @interface FullAppIntegrationTest {
}
