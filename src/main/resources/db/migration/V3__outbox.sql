-- Transactional outbox: domain events written in the same transaction as the business change,
-- then relayed to the message broker (or handled in-process) by OutboxRelay.
create table outbox_events (
    id              uuid         not null primary key,
    aggregate_type  varchar(50)  not null,
    aggregate_id    varchar(50)  not null,
    event_type      varchar(100) not null,
    payload         text         not null,
    status          varchar(20)  not null check (status in ('PENDING', 'PUBLISHED', 'FAILED')),
    attempts        integer      not null default 0,
    next_attempt_at timestamp(6) not null,
    last_error      varchar(1000),
    created_at      timestamp(6) not null,
    published_at    timestamp(6)
);

-- The relay polls "PENDING and due"; a partial index keeps that cheap as published rows pile up.
create index idx_outbox_pending on outbox_events (next_attempt_at) where status = 'PENDING';

-- Idempotent consumer: a message id is recorded in the same transaction that handles it.
create table processed_messages (
    message_id   uuid         not null primary key,
    processed_at timestamp(6) not null
);
