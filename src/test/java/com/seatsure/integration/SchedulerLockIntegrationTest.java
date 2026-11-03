package com.seatsure.integration;

import com.seatsure.service.TokenService;
import com.seatsure.support.FullAppIntegrationTest;
import com.seatsure.support.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Jobs marked @SchedulerLock run once across instances: a second run while the lock is held (as
 * another instance's cron firing at the same moment would be) is skipped.
 */
@FullAppIntegrationTest
class SchedulerLockIntegrationTest {

    @Autowired private TokenService tokens;
    @Autowired private TestFixtures fixtures;
    @Autowired private JdbcTemplate jdbc;

    private Long expiredTokenOfANewCustomer() {
        Long userId = fixtures.customer().user().id();
        jdbc.update("UPDATE refresh_tokens SET expires_at = now() - interval '3 days' WHERE user_id = ?", userId);
        return userId;
    }

    private int tokensOf(Long userId) {
        return jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, userId);
    }

    @Test
    @DisplayName("a purge that runs again while the first one's lock is held is skipped")
    void secondRunWithinTheLock_isSkipped() {
        Long first = expiredTokenOfANewCustomer();
        tokens.purgeExpired();
        assertThat(tokensOf(first)).as("the first run purges").isZero();
        assertThat(jdbc.queryForObject("SELECT lock_until > now() FROM shedlock WHERE name = 'purge-refresh-tokens'",
                Boolean.class)).as("held for lockAtLeastFor").isTrue();

        Long second = expiredTokenOfANewCustomer();
        tokens.purgeExpired(); // what a second instance would do at the same moment
        assertThat(tokensOf(second)).as("skipped: the lock is still held").isEqualTo(1);
    }
}
