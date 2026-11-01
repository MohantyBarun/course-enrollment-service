package com.student.studentservice.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A real PostgreSQL (same major version as production on Neon) for integration tests.
 * {@code @ServiceConnection} points the datasource at it; Flyway then builds the schema.
 * The container is reused by every test class that shares the same Spring context.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainer {

    public static final DockerImageName IMAGE = DockerImageName.parse("postgres:18-alpine");

    @Bean
    @ServiceConnection
    public PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>(IMAGE);
    }
}
