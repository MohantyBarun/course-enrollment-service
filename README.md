# 🎓 Student Management System

[![CI](https://github.com/MohantyBarun/course-enrollment-service/actions/workflows/ci.yml/badge.svg)](https://github.com/MohantyBarun/course-enrollment-service/actions/workflows/ci.yml)

A full-stack Student Management System: a **Spring Boot** REST API and a **React + TypeScript** frontend, deployed on free-tier cloud services.

| | Live |
|---|---|
| 🖥️ **App** | **[student-management-system-three-jet.vercel.app](https://student-management-system-three-jet.vercel.app)** |
| 📖 **API docs** | [Swagger UI](https://student-service-w3vr.onrender.com/swagger-ui.html) |

> Hosted on free tiers: the backend sleeps when idle, so the first request can take up to a minute.

A college **academic portal**:
- **Admins (academic office):** manage students and courses, take attendance, enter grades and post notices.
- **Students:** browse the course catalog, enroll and drop within credit and capacity limits, track attendance against the 75% rule, and see grades with SGPA/CGPA on the 10-point scale.

**👉 Try it without an account:** the login page has **Try as Admin** and **Try as Student** buttons backed by realistic demo data.

**👀 See the backend at work:** as admin, open two pages:
- **Emails:** every notification moving through the transactional outbox (queued, retrying, sent or logged only), with the reason shown and the temporary password masked. Register a student and the welcome email shows up within a second. A **Send test email** button checks the SMTP setup end to end. It only sends to allowlisted addresses, at most once every 5 minutes.
- **System:** live numbers from the metrics registry: traffic and latency, cache hit rates, outbox backlog, rate-limit rejections, circuit-breaker states and JVM heap. A button sends wrong logins until the per-IP limit answers **429**.

## 🛠️ Tech Stack
**Backend**
- Java 21
- Spring Boot 3.4.4 (Web, Data JPA, Security, Validation)
- PostgreSQL
- JWT (jjwt 0.12)
- Lombok
- Maven
- Flyway (schema migrations)
- Redis (cache, distributed rate limiting) with a Caffeine / in-memory fallback
- RabbitMQ (Spring AMQP) with a transactional outbox, retries and a dead-letter queue
- Resilience4j (circuit breakers), Spring Mail
- Micrometer: Prometheus metrics, OpenTelemetry tracing, structured (ECS) logs
- OpenAPI 3 / Swagger UI (springdoc)
- JUnit 5, Mockito, MockMvc, Testcontainers (PostgreSQL, RabbitMQ, Redis), Awaitility, JaCoCo

**Frontend** ([`frontend/`](frontend))
- React 18, TypeScript (strict), Vite
- React Router, Tailwind CSS
- Vitest, Testing Library

**Infrastructure**
- Docker, Docker Compose (local stack), GitHub Actions, Render (backend), Neon (PostgreSQL), Vercel (frontend)

## 🏗️ Backend architecture

```mermaid
flowchart LR
    C[Client] -->|HTTPS| F["Filters: request id → CORS → rate limit → JWT → idempotency"]
    F --> S[Controllers / services]
    S -->|"one transaction"| DB[(PostgreSQL<br/>business tables + outbox_events)]
    S <-->|cache| R[(Redis / Caffeine)]
    F <-->|counters| R
    DB -->|"OutboxRelay<br/>after commit + every 30s<br/>FOR UPDATE SKIP LOCKED"| T{transport}
    T -->|"publisher confirms"| MQ[[RabbitMQ<br/>studentservice.events]]
    T -.->|"no broker configured"| N
    MQ --> N["NotificationDispatcher<br/>idempotent consumer"]
    MQ -.->|"after retries"| DLQ[[notifications.email.dlq]]
    N -->|"circuit breaker"| SMTP[SMTP]
```

**Correctness under concurrency**
- **No overbooking:** enrolling locks the student row, then the course row (`SELECT … FOR UPDATE`, always in that order, so no deadlocks). Thirty students racing for five seats get exactly five enrollments, and one student enrolling in eight courses at once never exceeds the credit limit. Both are tested against real PostgreSQL with 30 concurrent threads.
- Constraint violations from races (e.g. a double submit) return **409**, not 500. Lock timeouts and deadlocks return 409 with "try again".

**Reliable async messaging (transactional outbox)**
- Registering a student or enrolling writes a domain event to `outbox_events` **in the same transaction** as the change. A rolled-back change never sends an email, and an email is never lost because the broker or SMTP was down.
- `OutboxRelay` delivers events right after commit (`@TransactionalEventListener` + `@Async`) and polls every 30s as a safety net. Each batch is claimed with `FOR UPDATE SKIP LOCKED`, so several instances can relay at once without double-sending. Failures back off exponentially (5s, 10s, 20s … capped at 1h), and after 10 attempts the event is marked `FAILED`. A metric tracks failed events.
- With `RABBIT_ENABLED=true`, events go to a RabbitMQ topic exchange with **publisher confirms**. Unroutable messages count as failures. The consumer retries with exponential backoff, then **dead-letters** the message to `notifications.email.dlq`. Without a broker, the same relay hands events to the consumer in-process.
- **Idempotent consumer:** the message id is recorded in `processed_messages` in the same transaction as the handling, so redelivered messages (at-least-once delivery) send one email.
- Temporary passwords travel through the outbox and the queue **AES-256-GCM encrypted**, never in plain text.

**Idempotent APIs**
- `POST /students`, `POST /courses`, `POST /courses/{id}/enroll` and `POST /announcements` accept an `Idempotency-Key` header (Stripe-style). A retry with the same key replays the original response (`Idempotent-Replayed: true`) instead of creating a second student or enrollment.
  - Same key while the first request is still running: **409**.
  - Same key with a different body: **422**.
  - 5xx responses aren't stored, so the request can be retried.
- Keys are scoped per user and expire after 24h. Stored responses are encrypted, because a registration response holds the temporary password.
- The frontend sends a fresh key per action and automatically retries network failures with the same key, which matters when the free-tier server is waking up.

**Caching**
- The course catalog is cached and evicted on any change to courses or seat counts.
- The admin dashboard aggregates are cached with a 60s TTL, so they can be up to a minute stale.
- Redis when `REDIS_ENABLED=true` (shared across instances, typed JSON values), Caffeine in-process otherwise.
- Evictions are **transaction-aware**: they run after commit, so a concurrent reader can't re-cache data that is about to change.
- Cache errors fall through to the database instead of failing the request.

**Rate limiting**
- Per client IP, using an atomic fixed-window counter: a Redis Lua script (`INCR` + `PEXPIRE` in one step) or in memory.
  - Login: 10/min
  - Demo login: 20/min
  - Token refresh: 30/min
  - Everything else: 300/min
- Responses carry `X-RateLimit-*` headers; rejected requests get **429** with `Retry-After`.
- If Redis is down, the limiter **fails open** behind a circuit breaker, so it doesn't add a timeout to every request.

**Resilience**
- Resilience4j circuit breakers around SMTP and Redis.
- Bounded retries with backoff in the outbox and in the RabbitMQ consumer, plus a DLQ.
- Render's health check uses `/actuator/health/liveness`, which never depends on Redis, RabbitMQ or SMTP. An outage of an optional dependency degrades a feature instead of restarting the container.

**Admin insight pages** (`GET /admin/notifications`, `GET /admin/system`)
- The Emails page joins the outbox rows with a `notification_log` table that the consumer writes in the same transaction as the de-duplication record. It shows each email as queued, retrying (with the next attempt time), in the broker, sent, logged only, or failed. Bodies are stored with the temporary password masked.
- The System page reads the Micrometer registry (the same data `/actuator/prometheus` exports) together with the cache statistics, outbox counts and Resilience4j state. Polling of `/admin` and `/actuator` is excluded from the traffic numbers.

**Observability**
- **Metrics:** `/actuator/prometheus`, protected with HTTP Basic (user `metrics`, `METRICS_PASSWORD`). It exposes:
  - HTTP latency histograms and JVM/Hikari/cache metrics
  - `@Observed` timers for `auth.login` and `enrollment.enroll`, tagged with the outcome
  - outbox pending/failed gauges and relay counters
  - rate-limit rejections
  - circuit-breaker state
- **Tracing:** Micrometer Tracing with an OpenTelemetry bridge. Trace and span ids appear in every log line, and spans are exported over OTLP when `MANAGEMENT_OTLP_TRACING_ENDPOINT` is set (e.g. Jaeger).
- **Request ids:** every response carries `X-Request-Id`. The caller's id is used if it's well-formed, otherwise a UUID is generated, and it goes into the MDC so a reported error can be matched to its logs.
- **Logs:** JSON (Elastic Common Schema) in production.

**Schema migrations**
- **Flyway** owns the schema and Hibernate only validates it (`ddl-auto=validate`), so a mismatched deploy fails at startup instead of altering production tables.
- The migrations:
  - `V1` is the baseline: the exact schema Hibernate had created.
  - `V2` adds missing foreign-key indexes.
  - `V3` adds the outbox and processed-message tables.
  - `V4` adds the idempotency-key table.
  - `V5` adds the notification log.
- Existing databases are baselined at V1 automatically.

## ✅ Features
| | Admin | Student |
|---|---|---|
| **Dashboard** | Totals, average attendance, students below 75%, course fill, students per department | CGPA, credits, attendance with warnings, current courses, notices |
| **Courses** | Create, edit, close or delete courses; roster with grades; attendance per class date | Catalog with seats left; enroll and drop |
| **Grades** | Assign O / A+ / A / B+ / B / C / P / F | Grades, SGPA per term, CGPA |
| **Announcements** | Post to everyone or one department | Feed filtered to their department |
| **Students** | Full CRUD, search, filters, per-student academic record | Own profile |

**Academic rules (enforced server-side)**
- Enrollment only while a course is open, within its capacity, and up to **24 credits per term**; no duplicate or repeat enrollment
- Dropping keeps the history; re-enrolling reactivates the same record
- Attendance is one record per student per class date, editable (upsert), never for future dates; **75% minimum** warnings
- Credit-weighted **SGPA/CGPA** on the 10-point scale (UGC CBCS); failed courses count with 0 points and earn no credits
- A course with enrollments can't be deleted, and its capacity can't drop below the seats already taken

**Demo mode** (`DEMO_ENABLED=true`)
- Seeds a realistic, date-relative dataset: 12 courses across two terms, 36 students in 3 departments, a graded previous term, about 7 weeks of attendance, and notices
- Passwordless **Try as Admin / Try as Student** (`POST /auth/demo-login`), so no credentials are published
- Optional nightly reset (`DEMO_RESET_CRON`) undoes visitors' changes; admin accounts are kept

**Students**
- Full CRUD for students (admin only)
- Pagination, sorting (whitelisted fields) and filtering (name/email search, department, branch) using JPA Specifications
- Students can view their own profile
- Updating a student's email keeps their login email in sync and revokes their sessions

**Authentication & security**
- Stateless JWT authentication with role-based access (`ROLE_ADMIN`, `ROLE_STUDENT`)
- Short-lived access tokens (15 min) and refresh tokens (7 days) stored in the database so they can be revoked on logout
- Temporary password on registration that expires after 48 hours and allows 3 attempts, emailed to the student through the outbox (and shown once to the admin)
- Enrollment confirmation emails
- First login is forced to a password change: until then the token only works for change-password, refresh and logout
- Brute-force protection: a 30-minute lockout after 5 failed attempts and a permanent block after 10
- BCrypt password hashing and a password-strength policy

**Engineering**
- DTO pattern with Bean Validation
- Global exception handling with consistent JSON errors and the correct HTTP status codes (including 409 for races, 422/409 for idempotency conflicts, 429 for rate limits)
- Audit fields (`createdAt`, `updatedAt`)
- Interactive API docs with Swagger UI
- `dev` / `prod` profiles, with all production secrets supplied through environment variables
- CORS configured for a separately hosted frontend
- Multi-stage Docker image, non-root and tuned for small containers
- CI on every push and PR: backend tests with coverage, frontend tests and build, and a Docker build

**Frontend**
- Admin dashboard: debounced search, department/branch filters, sortable columns, pagination, and create/edit/delete dialogs
- Temporary password shown once, with a copy button, after registering a student
- First-login flow that forces a password change, with a live password-policy checklist
- Student profile page
- Transparent token refresh (one request at a time) and automatic sign-out when the session expires
- Role-based route guards, plus a "server is waking up" notice for free-tier cold starts
- Accessible forms and dialogs (labels, `aria-*`, keyboard support), responsive layout

## 🚀 Setup & Run

### Prerequisites
- Java 21
- PostgreSQL
- Node.js 20+ (frontend)

### Environment Variables
| Variable | Required | Description |
|---|---|---|
| `JWT_SECRET` | Yes | Random secret, at least 32 characters |
| `DB_URL` | No (dev) | Default `jdbc:postgresql://localhost:5432/studentdb` |
| `DB_USERNAME` / `DB_PASSWORD` | Yes | Database credentials |
| `SPRING_PROFILES_ACTIVE` | No | `dev` (default) or `prod` |
| `CORS_ALLOWED_ORIGINS` | No | Comma-separated frontend origins. Default `http://localhost:5173,http://localhost:3000` |
| `ADMIN_EMAIL` / `ADMIN_PASSWORD` | Prod | Initial admin account, created on startup if it doesn't exist |
| `PORT` | No | Port to listen on (default `8080`; set automatically by Render) |
| `DEMO_ENABLED` | No | `true` seeds demo data and enables the demo logins (public demo deployments only) |
| `DEMO_RESET_CRON` | No | Spring cron for a demo reset, e.g. `0 0 3 * * *`. **Wipes all students and academic data** (admins kept) |
| `REDIS_ENABLED` / `REDIS_URL` | No | `true` + e.g. `rediss://default:<password>@<host>:6379` for a shared cache and rate limits. Default: in-process |
| `RABBIT_ENABLED` / `RABBITMQ_URL` | No | `true` + e.g. `amqps://<user>:<password>@<host>/<vhost>` to relay events through RabbitMQ. Default: in-process |
| `SPRING_MAIL_HOST` / `SPRING_MAIL_PORT` / `SPRING_MAIL_USERNAME` / `SPRING_MAIL_PASSWORD` | No | SMTP server for real emails (e.g. Brevo, port 587). Without it emails are only logged |
| `MAIL_ALLOWED_RECIPIENTS` | No | Comma-separated addresses or `@domain`s that get real email. **Required for real email in demo mode** (visitors can register any address); elsewhere it restricts who gets real email. A registered student only receives the welcome email if their address is on this list |
| `MAIL_FROM` / `APP_SIGN_IN_URL` | No | Sender address (must be verified with your SMTP provider), and a sign-in link to include in emails |
| `METRICS_PASSWORD` | No | Enables `/actuator/prometheus` for user `metrics` (HTTP Basic) |
| `MANAGEMENT_OTLP_TRACING_ENDPOINT` | No | OTLP endpoint for traces, e.g. `http://localhost:4318/v1/traces` |
| `DATA_ENCRYPTION_KEY` | No | Base64 32-byte AES key for encrypted data at rest. Default: derived from `JWT_SECRET` |
| `RATE_LIMIT_ENABLED` | No | `false` turns rate limiting off (default `true`) |

### Run
```bash
./mvnw spring-boot:run
```

- API docs (Swagger UI): http://localhost:8080/swagger-ui.html
- Health check: http://localhost:8080/actuator/health
- In the `dev` profile, an admin account is seeded: `admin@college.com` / `Admin@123`.

### Run the full stack locally (Docker)
`docker compose up -d` starts PostgreSQL, Redis, RabbitMQ, Jaeger, Prometheus and Grafana. Then:

```bash
DB_URL=jdbc:postgresql://localhost:5432/studentdb DB_USERNAME=student DB_PASSWORD=student \
REDIS_ENABLED=true RABBIT_ENABLED=true METRICS_PASSWORD=local-metrics \
MANAGEMENT_OTLP_TRACING_ENDPOINT=http://localhost:4318/v1/traces \
JWT_SECRET=local-dev-secret-that-is-at-least-thirty-two-characters ./mvnw spring-boot:run
```

- RabbitMQ UI (queues, the DLQ): http://localhost:15672 (guest/guest)
- Traces: http://localhost:16686 (Jaeger)
- Metrics: http://localhost:9090 (Prometheus)
- Dashboards: http://localhost:3001 (Grafana, admin/admin; Prometheus and Jaeger are pre-provisioned as data sources)

### Frontend
```bash
cd frontend
cp .env.example .env.local    # VITE_API_BASE_URL=http://localhost:8080
npm install
npm run dev                   # http://localhost:5173
```

## 📌 API Endpoints

### Auth
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| POST | `/auth/login` | Public | Log in and receive access + refresh tokens |
| POST | `/auth/refresh` | Public (refresh token) | Get a new access token |
| POST | `/auth/logout` | Public (refresh token) | Revoke all of the user's refresh tokens |
| POST | `/auth/change-password` | Authenticated | Change your own password |

### Students
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| POST | `/students` | Admin | Register a student (returns `201` + `Location`) |
| GET | `/students` | Admin | List students (paginated, filterable) |
| GET | `/students/{id}` | Admin | Get a student |
| PUT | `/students/{id}` | Admin | Update a student |
| DELETE | `/students/{id}` | Admin | Delete a student and their account (`204`) |
| GET | `/students/me` | Student | View your own profile |

### Courses & academics
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| GET | `/courses` | Signed in | Catalog with seats taken (`search`, `department`, `term`) |
| POST / PUT / DELETE | `/courses`, `/courses/{id}` | Admin | Manage courses |
| GET | `/courses/{id}/roster` | Admin | Students with grades and attendance |
| GET / POST | `/courses/{id}/attendance` | Admin | Attendance sheet for a date / record or update it |
| POST / DELETE | `/courses/{id}/enroll` | Student | Enroll / drop |
| PUT | `/enrollments/{id}/grade` | Admin | Assign a grade (completes the course) |
| GET | `/students/me/academics` | Student | Own record: courses, grades, attendance, SGPA/CGPA |
| GET | `/students/{id}/academics` | Admin | A student's record |
| GET / POST / DELETE | `/announcements` | Read: signed in · write: admin | Notices (students see general plus their department's) |
| GET | `/dashboard/admin`, `/dashboard/student` | Per role | Dashboards |
| GET / POST | `/auth/demo`, `/auth/demo-login` | Public | Demo availability / passwordless demo sign-in |

**Headers:** send `Idempotency-Key: <uuid>` on the POSTs above to make retries safe. Every response has `X-Request-Id`, and rate-limited responses have `X-RateLimit-Limit`, `X-RateLimit-Remaining` and, on 429, `Retry-After`.

**Student list query parameters:** `search`, `department`, `branch`, `page` (default `0`), `size` (default `10`, max `100`), `sortBy` (`id`, `name`, `email`, `department`, `branch`, `dateOfBirth`, `createdAt`), `direction` (`asc`/`desc`).

### Example: register a student
```http
POST /students
Authorization: Bearer <admin access token>
Content-Type: application/json

{
  "name": "John Doe",
  "email": "john@gmail.com",
  "department": "Engineering",
  "branch": "CSE",
  "dateOfBirth": "2003-05-14",
  "presentAddress": "Bhubaneswar",
  "permanentAddress": "Cuttack"
}
```

### Typical first-login flow
1. The admin registers the student. The student is emailed the temporary password, and the response also contains it once for the admin (it is never shown again).
2. The student calls `POST /auth/login` with the temporary password and gets a restricted token (`isFirstTimeLogin: true`).
3. The student calls `POST /auth/change-password` with `oldPassword`, `newPassword` and `confirmNewPassword`.
4. The student calls `POST /auth/refresh` to get a normal access token.

## 🧪 Testing

```bash
./mvnw test                 # backend: 230 tests (20 of them need Docker, see below)
cd frontend && npm test     # frontend: 60 tests
```

### Backend
Unit and web-layer tests need nothing installed. The **integration tests use Testcontainers** (real PostgreSQL 18, RabbitMQ and Redis in Docker). They're skipped automatically when Docker isn't available, and CI always runs them.

| Layer | Tooling | What's covered |
|---|---|---|
| Services | JUnit 5, Mockito, AssertJ | Every login path (temporary password, 30-min lockout, permanent block, auto-unblock), password change, refresh/logout, all student CRUD paths, and the outbox event written on registration (with the encrypted password) |
| Web/security | `@WebMvcTest`, MockMvc, real signed JWTs | Role-based access, first-login restriction, public vs authenticated endpoints, validation and error → HTTP status mapping (incl. 409 for constraint races), request ids |
| Persistence | `@DataJpaTest`, **Testcontainers PostgreSQL** | Flyway migrations + Hibernate schema validation, Specifications, nested-field sorting and paging, shared-primary-key mappings, auditing, token revoke/delete queries |
| Concurrency | `@SpringBootTest`, Testcontainers | 30 threads racing for 5 seats → exactly 5 enrollments; 8 concurrent enrollments by one student → exactly 24 credits |
| Outbox & messaging | `@SpringBootTest`, Testcontainers PostgreSQL + **RabbitMQ**, Awaitility | Emails sent after commit (once each); no event for a rolled-back transaction; publisher confirms; duplicate messages processed once; a failing message retried, then dead-lettered |
| Idempotency | MockMvc over the full app + unit tests | Replay of the stored response, 409 for concurrent duplicates, 422 for a reused key with a different body, keys released after 5xx, encrypted storage |
| Rate limiting & cache | Unit tests + **Testcontainers Redis** | Limits per rule and client, concurrent counting, 429 + `Retry-After` through the real filter chain, Redis Lua script and TTL, fail-open, typed JSON cache values |
| Observability | Full app | Liveness public, `/actuator/prometheus` protected and exposing the business metrics |
| Academics | JUnit 5, Mockito | Enrollment rules (closed, full, duplicate, completed, credit limit, re-enroll), lock order, drop rules, grading, SGPA/CGPA maths, attendance upsert, course rules |
| Demo & JWT | `@DataJpaTest`, JUnit 5 | Seeding and reset against PostgreSQL; token claims, types, and rejection of expired, forged and tampered tokens |

Each bug fixed earlier has a regression test marked `regression:` in its display name. A coverage report is generated at `target/site/jacoco/index.html`.

### Frontend
Vitest and Testing Library, with `fetch` mocked:
- **API client:** auth header, single-flight token refresh, sign-out when the refresh token is rejected, parsing of errors and field errors, and `Idempotency-Key` retries on network failures
- **Route guards:** sign-in required, first-login password change enforced, role checks
- **Academics:** demo login, both dashboards, catalog enroll/refusal/full/closed states, grading from the roster, taking attendance, announcements per role
- **Pages:** login (including a generic message that doesn't reveal whether an account exists), student list, search, create with the temporary-password dialog, client-side validation, duplicate-email handling, delete, and the password-change flow

## ☁️ Deployment (free tier)

| Component | Host |
|---|---|
| Backend | [Render](https://render.com) free web service (Docker) |
| Database | [Neon](https://neon.tech) free PostgreSQL |
| Frontend | [Vercel](https://vercel.com) (static site from `frontend/`) |

> Render's free services sleep after about 15 minutes of inactivity, so the first request afterwards can take up to a minute while the app starts.

### 1. Database (Neon)
1. Create a project, choosing a region close to your Render region (for example AWS Singapore).
2. Copy the **direct** connection string (the host without `-pooler`). The app keeps its own connection pool, and Hibernate applies schema changes at startup. Neon shows a URL like `postgresql://USER:PASSWORD@HOST/DBNAME?sslmode=require`. Spring needs it split up:
   - `DB_URL` = `jdbc:postgresql://HOST/DBNAME?sslmode=require`
   - `DB_USERNAME` = `USER`
   - `DB_PASSWORD` = `PASSWORD`

### 2. Backend (Render)
1. Push this repo to GitHub, then in Render choose **New → Blueprint** and select the repo. Render reads [`render.yaml`](render.yaml).
2. Enter the secrets it asks for: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `ADMIN_EMAIL`, `ADMIN_PASSWORD` and `CORS_ALLOWED_ORIGINS` (your Vercel URL). `JWT_SECRET` is generated automatically.
3. Render builds the [`Dockerfile`](Dockerfile) and checks `/actuator/health`. Swagger UI is then live at `https://<your-service>.onrender.com/swagger-ui.html`.

Docker is not needed on your machine: Render and GitHub Actions build the image. Render only redeploys when backend files change (`buildFilter` in `render.yaml`).

### 3. Frontend (Vercel)
1. In Vercel choose **Add New → Project**, import the repo, and set **Root Directory** to `frontend`. Vite is detected automatically.
2. Add the environment variable `VITE_API_BASE_URL` = `https://<your-service>.onrender.com`.
3. Deploy, then add the Vercel URL to `CORS_ALLOWED_ORIGINS` on Render (comma-separated with any existing origins).

[`vercel.json`](frontend/vercel.json) adds SPA routing and security headers, and skips builds when nothing under `frontend/` changed.

### 4. Optional free add-ons
Everything works without these (in-process fallbacks). To run the full distributed setup:

| Add-on | Free service | Render environment variables |
|---|---|---|
| Redis | [Upstash](https://upstash.com) Redis database | `REDIS_ENABLED=true`, `REDIS_URL=rediss://default:<password>@<host>:6379` |
| RabbitMQ | [CloudAMQP](https://www.cloudamqp.com) "Little Lemur" plan | `RABBIT_ENABLED=true`, `RABBITMQ_URL=<the AMQPS URL>` |
| Metrics | any Prometheus / Grafana Cloud scraper | `METRICS_PASSWORD=<random>` |
| Email | [Brevo](https://www.brevo.com) SMTP (300 emails/day free) | `SPRING_MAIL_HOST=smtp-relay.brevo.com`, `SPRING_MAIL_PORT=587`, `SPRING_MAIL_USERNAME=<SMTP login>`, `SPRING_MAIL_PASSWORD=<SMTP key>`, `MAIL_FROM=<verified sender>`, `MAIL_ALLOWED_RECIPIENTS=<your address>` |

Database schema changes are applied by Flyway on startup. If Hibernate's validation ever fails, the new version won't pass Render's health check, and Render keeps the previous version running.

## 🔮 Upcoming
- Store hashed (not raw) refresh tokens and rotate them on use, with reuse detection
- Replace the permanent account lock with per-account backoff (the IP rate limit already slows guessing)
- Load test (k6) with published latency numbers
