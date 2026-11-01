package com.seatsure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Times are stored as instants (timestamptz). "Today", show dates and day boundaries are in the
 * audience's zone, not the server's: the server runs in UTC, the cinemas in Asia/Kolkata.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public ZoneId audienceZone(@Value("${app.zone:Asia/Kolkata}") String zone) {
        return ZoneId.of(zone);
    }
}
