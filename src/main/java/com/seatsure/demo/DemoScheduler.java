package com.seatsure.demo;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Seeds demo data on startup and, if app.demo.reset-cron is set (e.g. "0 0 3 * * *"),
 * resets it on that schedule so changes made by visitors don't accumulate. Cron "-" disables the reset.
 */
@Component
@EnableScheduling
@Order(100) // after the regular admin seeder
@RequiredArgsConstructor
public class DemoScheduler implements ApplicationRunner {

    private final DemoDataService demo;

    @Override
    public void run(ApplicationArguments args) {
        demo.seedIfEmpty();
    }

    @Scheduled(cron = "${app.demo.reset-cron:-}", zone = "Asia/Kolkata")
    public void scheduledReset() {
        demo.reset();
    }
}
