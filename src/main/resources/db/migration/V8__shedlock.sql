-- ShedLock: jobs that must run once across all instances (the demo reset, the nightly purges)
-- take a row here first. lock_until is checked against the database clock, so instances whose
-- clocks disagree still agree on who holds a lock.
create table shedlock (
    name       varchar(64)  primary key,
    lock_until timestamptz  not null,
    locked_at  timestamptz  not null,
    locked_by  varchar(255) not null
);
