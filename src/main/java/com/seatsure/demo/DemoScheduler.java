package com.seatsure.demo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Seeds demo data on startup and, if app.demo.reset-cron is set (e.g. "0 0 3 * * *"),
 * resets it on that schedule so changes made by visitors don't accumulate. Cron "-" disables the reset.
 *
 * <p>Both run once across instances: two instances seeding an empty database at the same time, or
 * resetting it at the same moment, would wipe each other's work.
 */
@Slf4j
@Component
@Order(100) // after the regular admin seeder
@RequiredArgsConstructor
public class DemoScheduler implements ApplicationRunner {

    private static final Duration SEED_LOCK_AT_MOST = Duration.ofMinutes(10);

    private final DemoDataService demo;
    private final LockingTaskExecutor locks;
    private final Clock clock;

    /** The instance that gets the lock seeds; any other starting at the same time skips (it's being done). */
    @Override
    public void run(ApplicationArguments args) {
        AtomicBoolean ran = new AtomicBoolean();
        locks.executeWithLock((Runnable) () -> {
            ran.set(true);
            demo.seedIfEmpty();
        }, new LockConfiguration(clock.instant(), "demo-seed", SEED_LOCK_AT_MOST, Duration.ZERO));
        if (!ran.get()) {
            log.info("Demo mode: another instance is seeding; skipping");
        }
    }

    /** lockAtLeastFor: an instance whose clock is a little behind can't start a second reset afterwards. */
    @Scheduled(cron = "${app.demo.reset-cron:-}", zone = "Asia/Kolkata")
    @SchedulerLock(name = "demo-reset", lockAtLeastFor = "PT5M", lockAtMostFor = "PT15M")
    public void scheduledReset() {
        demo.reset();
    }
}
