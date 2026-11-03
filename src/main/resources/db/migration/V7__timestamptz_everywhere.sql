-- The identity and messaging tables came from the original app and stored server-local wall-clock
-- times (timestamp without time zone), while the booking domain uses timestamptz. Make them all
-- timestamptz, so every table stores an absolute instant regardless of the JVM's time zone.
-- Existing values were written by a JVM running in UTC (the production container), so they are
-- read as UTC wall-clock times.

alter table users
    alter column created_at type timestamptz using created_at at time zone 'UTC',
    alter column updated_at type timestamptz using updated_at at time zone 'UTC';

alter table login_security
    alter column last_login_at   type timestamptz using last_login_at at time zone 'UTC',
    alter column last_failed_at  type timestamptz using last_failed_at at time zone 'UTC',
    alter column blocked_at      type timestamptz using blocked_at at time zone 'UTC',
    alter column unblocked_at    type timestamptz using unblocked_at at time zone 'UTC',
    alter column block_expiry_at type timestamptz using block_expiry_at at time zone 'UTC';

alter table refresh_tokens
    alter column expires_at type timestamptz using expires_at at time zone 'UTC',
    alter column revoked_at type timestamptz using revoked_at at time zone 'UTC',
    alter column created_at type timestamptz using created_at at time zone 'UTC';

alter table outbox_events
    alter column next_attempt_at type timestamptz using next_attempt_at at time zone 'UTC',
    alter column created_at      type timestamptz using created_at at time zone 'UTC',
    alter column published_at    type timestamptz using published_at at time zone 'UTC';

alter table processed_messages
    alter column processed_at type timestamptz using processed_at at time zone 'UTC';

alter table notification_log
    alter column created_at type timestamptz using created_at at time zone 'UTC';

alter table idempotency_keys
    alter column created_at type timestamptz using created_at at time zone 'UTC',
    alter column expires_at type timestamptz using expires_at at time zone 'UTC';
