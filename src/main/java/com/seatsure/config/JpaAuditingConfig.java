package com.seatsure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.time.Clock;
import java.util.Optional;

/**
 * Kept separate from the main application class so that sliced tests such as
 * {@code @WebMvcTest}, which don't load JPA, can start without a JPA metamodel.
 * {@code @CreatedDate}/{@code @LastModifiedDate} read the same {@link Clock} as the rest of the code.
 */
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaAuditingConfig {

    @Bean
    public DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(clock.instant());
    }
}
