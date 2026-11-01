-- PostgreSQL doesn't index foreign keys automatically. These back the hot lookups:
-- seat counts per course (enrollment checks, catalog) and token revocation per user (logout).
create index if not exists idx_enrollments_course_status on enrollments (course_id, status);
create index if not exists idx_refresh_tokens_user on refresh_tokens (user_id);
