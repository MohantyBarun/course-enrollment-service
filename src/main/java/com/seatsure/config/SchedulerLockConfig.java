package com.seatsure.config;

import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Jobs that must run once however many instances are up take a lock in the {@code shedlock} table
 * ({@code @SchedulerLock}). Jobs that already share work safely ({@code SKIP LOCKED} claims: hold
 * expiry, outbox and webhook delivery) and per-instance jobs (SSE heartbeats) don't use it.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(JdbcTemplate jdbc) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(jdbc)
                .usingDbTime() // the database's clock decides, not each instance's
                .build());
    }

    /** For one-off work outside @Scheduled, such as seeding the demo at startup. */
    @Bean
    public LockingTaskExecutor lockingTaskExecutor(LockProvider lockProvider) {
        return new DefaultLockingTaskExecutor(lockProvider);
    }
}
