package com.seatsure.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("10") int loginPerMinute,
        @DefaultValue("5") int registerPerMinute,
        @DefaultValue("20") int demoLoginPerMinute,
        @DefaultValue("30") int refreshPerMinute,
        @DefaultValue("300") int apiPerMinute) {
}
