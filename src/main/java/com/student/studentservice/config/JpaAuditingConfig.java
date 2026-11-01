package com.student.studentservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Kept separate from the main application class so that sliced tests such as
 * {@code @WebMvcTest}, which don't load JPA, can start without a JPA metamodel.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
