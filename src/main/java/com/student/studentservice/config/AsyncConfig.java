package com.student.studentservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @Async runs on Spring Boot's applicationTaskExecutor (spring.task.execution.*);
 * @Scheduled jobs: outbox polling and the nightly purges.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {
}
