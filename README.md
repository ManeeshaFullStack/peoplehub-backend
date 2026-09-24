# peoplehub-backend

Spring Boot API for **PeopleHub**, a multi-role HR operations SaaS (attendance, leave, approvals, org structure,
notifications, reports). The target design (master spec v9) is a **multi-organization** service in which every
organization is a completely private workspace; that tenant model is **not implemented yet** (see "Scope" below).

- **Source of truth:** [`PROJECT_MASTER_SPEC.md`](PROJECT_MASTER_SPEC.md) (v9). Scope changes update the spec first,
  through a branch and pull request.
- **Engineering rules:** [`CLAUDE.md`](CLAUDE.md) (workflow, architecture, security, testing, known spec issues).
- **Status:** Phase B0 (Foundation): `b0-1` to `b0-6` are merged; `b0-7` (Dockerfile and the backend development
  compose) is implemented on its branch and awaiting review. The app is a runnable foundation with no business
  endpoints yet.

## Scope: implemented now vs specified for later

**Implemented (b0-1 to b0-7):** build and CI (tests, Spotless, secret scan, a compose smoke test); PostgreSQL with Flyway
migrations and Redis; the API standards (`/api/v1` base path, pagination and sort envelope, one RFC 9457 problem-error
shape, correlation ids, OpenAPI via springdoc); structured JSON logging with a request log; Sentry with PII scrubbing
(off unless a DSN is set); Actuator health and readiness probes; graceful shutdown; a scheduler (`@Scheduled` guarded by
ShedLock, with `JobRunner`); an append-only audit log with separate database roles for migrations and for the running
application; and a hardened container image with a backend development stack (`docker compose`). Sections below describe
these.

**Specified by v9 but not implemented (future phases, mainly B2, F1 and security work):** multi-organization SaaS with
hard tenant isolation (`organization_id` is the tenant boundary, and no tenant can discover another); founder-only
public organization registration, where the founder becomes the first Super Admin and goes through email verification,
mandatory MFA and organization setup; invitation-only Employees and Admins; one login (Organization + company email +
password) for every role; and deactivation that revokes access immediately while keeping history. The business
modules (employees, attendance, leave, approvals, reports) also come in later phases. Details:
[`CLAUDE.md`](CLAUDE.md) ("v9 adoption") and the spec.

## Stack

Java 21, Spring Boot 4.1.1, Maven (wrapper), PostgreSQL 17, Flyway, Hibernate, Redis 7, Testcontainers, springdoc
OpenAPI, Actuator, Sentry and ShedLock. Later phases add the rest of the spec's stack (email, authentication, rate
limiting, reports and so on) as the build order (Section 17) reaches them.

## Prerequisites

- **JDK 21** (Temurin recommended). Check with `java -version`.
- **Docker** (Docker Desktop on Windows/macOS), **running**. Tests and local dev start real PostgreSQL and Redis
  containers through Testcontainers, so `./mvnw test` and `./mvnw verify` fail without it. Check with `docker info`.
- No global Maven needed: use the wrapper (`./mvnw`, or `mvnw.cmd` in Windows `cmd`/PowerShell).

## Common commands

| Task | Command |
|---|---|
| Build, run tests and lint (what CI runs) | `./mvnw verify` |
| Run tests only (needs Docker) | `./mvnw test` |
| Auto-fix formatting | `./mvnw spotless:apply` |
| Check formatting only | `./mvnw spotless:check` |
| **Run the app locally** (throwaway Postgres + Redis containers, no setup) | `./mvnw spring-boot:test-run` |
| Run the containerised stack (needs `.env`, see "Docker") | `docker compose up --build --wait` |
| Verify that stack end to end (own project, own ports; what CI's `compose-smoke` runs) | `bash docker/smoke.sh` |
| Run against your own Postgres/Redis (needs the environment below) | `./mvnw spring-boot:run` |
| Run the packaged jar (needs the environment below) | `java -jar target/peoplehub-backend-0.1.0-SNAPSHOT.jar` |

### Configuration

The app reads its connections from environment variables, using Spring's standard names. See
[`.env.example`](.env.example) for the full list; keep real values in a git-ignored `.env`.

| Variable | Purpose |
|---|---|
| `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | PostgreSQL, as the **runtime** role (least privilege) |
| `SPRING_FLYWAY_USER`, `SPRING_FLYWAY_PASSWORD`, optional `SPRING_FLYWAY_URL` | Migrations, as the **owner** role (see "Database roles") |
| `PEOPLEHUB_DB_RUNTIME_ROLE` | Name of the runtime role the migrations grant privileges to (default `peoplehub_app`) |
| `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_PORT`, `SPRING_DATA_REDIS_PASSWORD` | Redis |
| `PEOPLEHUB_JWT_SIGNING_KEY`, `PEOPLEHUB_JWT_SIGNING_KEY_ID` | Access-token signing key and its id; required (see "Authentication") |
| `PEOPLEHUB_SECURITY_APP_ORIGIN` | The frontend's origin, for CORS and the Origin check; optional |

The database settings have no defaults (except the role name): a missing one stops startup immediately.
`spring-boot:test-run` needs none of these. Flyway applies the migrations in `src/main/resources/db/migration` on
startup. Migrations are forward-only; never edit one that has already been merged, add a new `V<n>__description.sql`
instead.

### Database roles

The application uses **two PostgreSQL roles** (Spec 15, CLAUDE.md §9):

| Role | Used for | Configured by |
|---|---|---|
| **Owner** (migration role) | Runs Flyway; owns the tables. Not a superuser. | `SPRING_FLYWAY_USER` / `SPRING_FLYWAY_PASSWORD` |
| **Runtime** (`peoplehub_app` by default) | What the running application connects as. Gets only the privileges each migration grants. | `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD`, name in `PEOPLEHUB_DB_RUNTIME_ROLE` |

**Flyway never creates roles** (`CREATE ROLE` needs elevated privileges), so both roles must exist **before the first
start**. Provision them once per database as part of infrastructure setup, as a database administrator, for example:

```sql
CREATE ROLE peoplehub_owner LOGIN PASSWORD '...';          -- migration/owner role
CREATE ROLE peoplehub_app   LOGIN PASSWORD '...';          -- runtime role
CREATE DATABASE peoplehub OWNER peoplehub_owner;
\c peoplehub
GRANT CONNECT ON DATABASE peoplehub TO peoplehub_app;
GRANT USAGE ON SCHEMA public TO peoplehub_app;             -- what it may do to each table comes from the migrations
```

If the runtime role is missing, the first migration that grants it privileges (`V3`, and now `V4`) fails at once with
`Runtime role "..." does not exist`, and nothing is half-applied. Tests and `spring-boot:test-run` provision the roles automatically (`src/test/resources/testcontainers/
db-roles.sql`), and use the container's own superuser for both Flyway and the application, except in the tests that prove
the privilege boundary.

- **The role name is validated before it is used.** It is substituted into migration SQL, so
  `RuntimeRolePlaceholderGuard` refuses to start the application unless `PEOPLEHUB_DB_RUNTIME_ROLE` matches
  `[a-z_][a-z0-9_]{0,62}` (a plain lower-case identifier). The migration then re-checks it and quotes it as an identifier.
- **Grants are per table, in the migration that creates the table.** There are no default privileges, so a new table
  cannot give the runtime role `UPDATE` or `DELETE` by accident. `RuntimePrivilegesTest` lists every table and the exact
  privileges the runtime role has; a change to either fails it until the test is changed on purpose.
- **Residual risk:** Flyway runs inside the application, so the application's environment holds the owner credentials.
  A stronger setup runs migrations as a separate job with the owner credentials (`SPRING_FLYWAY_ENABLED=false` on the
  application). The development stack (see "Docker") does the same, deliberately; separating them is a deployment
  decision, tracked for the hosting choice.

Formatting is enforced by [Spotless](https://github.com/diffplug/spotless) with google-java-format in **AOSP** style
(4-space indent, 100 columns). `./mvnw verify` fails on badly formatted code; run `./mvnw spotless:apply` to fix it.

### Troubleshooting: `PKIX path building failed` on Windows

If Maven cannot download dependencies and reports `PKIX path building failed`, something on your machine (for example
antivirus HTTPS scanning) re-signs TLS traffic with a root certificate that Windows trusts but Java does not. Make Maven
use the Windows certificate store, for that shell only:

```bash
export MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT"   # PowerShell: $env:MAVEN_OPTS="..."
```

Do not disable certificate checking.

### Troubleshooting: Docker

If tests fail while starting containers, check that Docker Desktop is running (`docker info`). The first run downloads
the `postgres:17-alpine` and `redis:7-alpine` images, which can take a minute. Testcontainers removes its containers when
the run ends, even if the process is killed.

## Docker

Two things live here (Spec 16.4, D16): the backend **image** (`Dockerfile`) and the backend **development stack**
(`docker-compose.yml`): the backend, PostgreSQL, Redis and [Mailpit](https://mailpit.axllent.org/) (a dev-only mail
catcher for the outbox emails that arrive in B1). There is **no frontend service**: the frontend does not exist until F0.
The complete platform compose, with the frontend, moves to infrastructure ownership (`peoplehub-infra`) when it does; this
file stays the backend-scoped stack, and its project name (`peoplehub-backend-dev`) cannot collide with that one.

```bash
cp .env.example .env         # then set the four secrets in the "Docker Compose" section and the JWT key
                             # in "Authentication": none of them has a default
docker compose up --build --wait
curl http://127.0.0.1:8080/actuator/health/readiness     # {"status":"UP"}
docker compose down          # keeps the data; add -v to delete it
```

The backend is on `http://127.0.0.1:8080` and Mailpit's web UI on `http://127.0.0.1:18025` (both bound to loopback; change
the ports with `PEOPLEHUB_BACKEND_PORT` and `PEOPLEHUB_MAILPIT_UI_PORT`). PostgreSQL and Redis are **not** published; to
reach them from the host, add a git-ignored `docker-compose.override.yml`.

**The image.** Multi-stage: a JDK 21 stage builds the jar, a JRE-only stage runs it, as a numeric non-root user (`10001`),
in UTC (`TZ=UTC`, so log timestamps are UTC wherever it runs), with an Actuator liveness health check. The base is
Temurin on Ubuntu noble (glibc), chosen over Alpine for reliability, not size: later phases add native-code libraries and
Apache POI needs fonts. Base images are pinned by digest for reproducibility. **Nothing updates those digests
automatically** (there is no Dependabot configuration yet): bump them on purpose and rebuild.

**What every container has.** A read-only root filesystem, no Linux capabilities, `no-new-privileges`, a non-root user, and
CPU/memory limits. The only writable places are small `tmpfs` mounts: `/tmp` (the JVM, Tomcat, PostgreSQL and Mailpit need
scratch space; `noexec` works) and PostgreSQL's socket directory.

**Roles and secrets.** On the first start (an empty volume) `docker/postgres/init/01-roles.sh` creates the owner and
runtime roles (Flyway never creates roles, see "Database roles"). The bootstrap superuser exists only inside the database
container. The four secrets have no defaults; `${VAR:?}` makes Compose refuse to start without them. They are ordinary
environment variables, so `docker inspect` shows them: fine for development, not for production (hosting is an open item,
Spec 19). The roles are created once: to change one later use `ALTER ROLE`, or `docker compose down -v` to start over.

**Shutdown.** The backend's `stop_grace_period` is a fixed **90s**: shutdown runs the web-server phase and then the
scheduler phase, each up to `SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE` (30s), plus a 5s unwind period, so 65s in the
worst case, and Docker's default of 10s would kill it mid-shutdown. If you raise the timeout, raise the grace period
(the smoke test fails when it is below 2 x the timeout + 5s + a margin). The JVM exits with code 143 on SIGTERM; that is
normal.

**Verifying it.** `bash docker/smoke.sh` builds the image, starts the stack from empty volumes with random throwaway
secrets, and checks: health and readiness, the RFC 9457 error shape, that Flyway ran as the owner role while the
application runs as the runtime role and **cannot update, delete or truncate the audit log**, hardening and limits on all four
containers, loopback-only ports, a restart on the existing volumes, and a graceful shutdown inside the grace period. It uses
its own project (`peoplehub-smoke`), volumes and ports, so it never touches your dev stack. CI runs it as the
`compose-smoke` job, which is **not** a required check yet. Set `SMOKE_KEEP=1` to leave the stack running afterwards.

Not covered here: a real "under load" shutdown (in-flight request draining is proven by `GracefulShutdownTest`), and the
field-level validation error of the B0 exit criteria, which needs a write endpoint (proven by tests until B2).

## Project layout

```
src/main/java/com/peoplehub/            # package-by-feature; root package com.peoplehub
  PeopleHubApplication.java             # entry point
  common/time/TimeConfig.java           # injectable Clock (never call Instant.now() directly)
  common/api/                           # API standards shared by every endpoint (see "Writing an endpoint")
    error/                              # GlobalExceptionHandler, ProblemType, ApiProblemException
    paging/                             # @PageParams, PageQuery, PageResponse
    correlation/                        # X-Correlation-Id filter
    openapi/                            # springdoc configuration
  common/logging/                       # actor id, request log, message-free stack traces
  common/observability/                 # Sentry PII scrubber
  common/scheduling/                    # ShedLock config, JobRunner (see "Scheduled jobs")
  common/database/                      # runtime role name guard (see "Database roles")
  audit/                                # append-only audit writer (see "Audit log")
  security/                             # filter chain, public endpoints, 401/403, CORS, password hashing
    jwt/                                # ES256 keys, access-token issuing and verification
    principal/                          # AuthenticatedPrincipal: who is calling, and their tenant
  auth/                                 # login, refresh, logout, lockout, change password (see "Authentication")
  profile/                              # GET /me, welcome acknowledgement, POST /me/password
  passwordreset/                        # forgot and reset password (see "Passwords")
  invitation/                           # invitations: invite, resend, revoke, preview, accept (see "Invitations")
src/main/resources/
  application.yml                       # non-secret settings only
  application-local.yml                 # `local` profile: readable console, DEBUG (developer machines only)
  db/migration/                         # Flyway migrations (forward-only)
src/test/java/com/peoplehub/
  support/                              # @IntegrationTest, @ApiWebTest, Testcontainers (Postgres, Redis)
  TestPeopleHubApplication.java         # local dev entry point (spring-boot:test-run)
  common/api/testsupport/               # test-only sample endpoints (profile "api-test")
  db/, redis/, common/                  # tests
.env.example                            # environment variable names (no values)
Dockerfile, .dockerignore               # the backend image (see "Docker")
docker-compose.yml                      # the backend development stack
docker/                                 # postgres/init/01-roles.sh (role bootstrap), smoke.sh (stack verification)
.github/                                # CI workflow (build, secret scan, compose smoke)
```

Two test styles: `@ApiWebTest` is a fast web-layer slice (no Docker) for controller, error and pagination
behaviour; `@IntegrationTest` (both from `support/`) starts the full app with real PostgreSQL and Redis and is for
anything touching the database, Flyway, Redis or OpenAPI generation.

## Writing an endpoint

The conventions in Spec section 13 are built in, so an endpoint only has to use them:

```java
@RestController
@RequestMapping("/admin/employees")                       // served at /api/v1/admin/employees
class EmployeeController {

    @GetMapping
    PageResponse<EmployeeDto> list(
            @PageParams(defaultSize = 25, defaultSort = "name,asc", sortable = {"name", "joinDate"})
                    PageQuery query) {
        return PageResponse.from(service.list(query.toPageable()), EmployeeDto::from);
    }

    @PostMapping
    EmployeeDto create(@Valid @RequestBody CreateEmployee body) { ... }
}
```

- **Base path:** never write `/api/v1`; it is added to every `@RestController` automatically.
- **Lists:** take a `PageQuery` with `@PageParams` and return `PageResponse<T>`
  (`{ items, page, size, totalElements, totalPages }`). Clients send `page` (from 0), `size` (default 20, at most
  100; larger is rejected, use an export) and repeatable `sort=field,asc|desc`. Bad values, unknown sort fields and
  sort fields you did not list are a 400 that says exactly what is allowed. Sort names must match entity properties.
- **Errors:** every error is one `application/problem+json` body (`type`, `title`, `status`, `detail`, `instance`,
  `correlationId`, and `fieldErrors` for validation). Validate DTOs with Bean Validation. To signal a domain error
  throw `ApiProblemException` with a `ProblemType` (add one if none fits); never build an error response by hand.
  5xx responses never contain exception text, and error bodies never echo the request path or rejected values.
- **Correlation id:** every response has an `X-Correlation-Id` header (the caller's if well formed, otherwise
  generated), and the same id is in the error body and the log context.
- **OpenAPI:** generated automatically from your controllers at `/v3/api-docs`, including the paging parameters
  (with limits and allowed sort fields) and the standard error responses. Swagger UI is off by default; enable it
  locally with `SPRINGDOC_SWAGGER_UI_ENABLED=true`, for example
  `SPRINGDOC_SWAGGER_UI_ENABLED=true ./mvnw spring-boot:test-run`, then open `/swagger-ui/index.html`.
- **Authentication:** every endpoint needs an access token unless it is listed in `security/PublicEndpoints`; see
  "Authentication" below.

## Authentication

Password login, JWT access tokens and rotating refresh tokens (b2-3), and the password policy, lockout, reset and
change (b2-5; the decisions are "B2-3 decisions" and "B2-5 decisions" in [`CLAUDE.md`](CLAUDE.md)). Not built yet: MFA
(b2-7), sessions list, logout-all and deactivation (b2-6), roles and permissions (b3-1), PostgreSQL row-level security
(b2-8), per-IP lockout (with the hosting decision) and request rate limiting (b13-1).

| Endpoint | What it does |
|---|---|
| `POST /api/v1/auth/login` | Body `{organization, email, password}`, the same for every role. Returns `{accessToken, tokenType, expiresIn, csrfToken}` and sets the refresh-token and CSRF cookies. Any failure is one generic 401. |
| `POST /api/v1/auth/refresh` | Rotates the refresh token and returns a new access token and CSRF token. Needs the cookies and `X-CSRF-Token`. |
| `POST /api/v1/auth/logout` | Ends the current session (its refresh and access tokens stop working immediately). Always 204. Needs `X-CSRF-Token` when a session cookie is sent. |
| `POST /api/v1/auth/forgot-password` | Body `{organization, email}`. Always 202 with the same message; emails a reset code only to an active account (see "Passwords"). |
| `POST /api/v1/auth/reset-password` | Body `{token, password, confirmPassword}`: sets a new password with the emailed code and ends every session. 204. |
| `GET /api/v1/me` | The caller's own profile, including `firstName` (derived from `name` on the server) and `welcomeSeenAt`. |
| `POST /api/v1/me/welcome/ack` | Records that the one-time welcome screen was seen (`welcomeSeenAt`); later calls change nothing. 204. |
| `POST /api/v1/me/password` | Body `{currentPassword, newPassword, confirmPassword}`: changes the caller's password; this session stays signed in, every other one ends. 204. |

How a client uses it:

- Keep the **access token** in memory (never in `localStorage`) and send it as `Authorization: Bearer <token>`. It is
  an ES256 JWT valid for 15 minutes.
- The **refresh token** lives only in an `HttpOnly`, `Secure`, `SameSite=Strict` cookie (`__Secure-peoplehub_rt`,
  path `/api/v1/auth`); JavaScript never sees it. A session lasts 30 days after its last refresh, and never more than 90
  days after login. Refresh one call at a time: the same refresh token used twice is treated as stolen and ends the
  whole session.
- Send the **CSRF token** from the last login/refresh response body in `X-CSRF-Token` on refresh and logout.
- Browsers must call from `PEOPLEHUB_SECURITY_APP_ORIGIN` (CORS, and an Origin check on login, refresh, logout, forgot
  and reset password). Unset means no browser origin is allowed; clients that send no `Origin` header are not affected.
- Every 401/403 is the standard problem body, and never says which check failed.

In the code, an authenticated controller receives the caller with `@AuthenticationPrincipal AuthenticatedPrincipal`.
Its `organizationId` is the only tenant a service may act in: never take an organization id (or the caller's own
employee id) from the request. It is verified against the database on every request: an employee or organization that
is no longer `ACTIVE`, or a session that was logged out, is rejected at once. A new endpoint is authenticated by
default; making one public means adding it to `PublicEndpoints` on purpose, and `SecurityBaselineTest` fails for any
non-public endpoint that answers without a token.

### Passwords

- **Policy** (everywhere a password is set: registration, invitation acceptance, reset, change): 12 to 128 characters,
  not in the bundled breached-password list, and not containing the organization, the person's name or their email's
  local part. No composition rules. Only an Argon2id hash is stored. The breached list
  (`src/main/resources/security/breached-passwords.txt`, checked offline; source and licence in the `.NOTICE.txt` next
  to it) is loaded at startup, and the application refuses to start without it.
- **Lockout** (per account): after 5 consecutive failed sign-ins the account is locked for 1 minute, doubling on each
  further failure up to 30 minutes (`peoplehub.auth.lockout.*`, `PEOPLEHUB_AUTH_LOCKOUT_THRESHOLD`, `_INITIAL`,
  `_MAX`). A locked account answers exactly like a wrong password, and attempts while locked neither count nor extend
  the lock. A wrong current password on change password counts too. A successful sign-in or a reset clears it. Each
  lock is audited (`ACCOUNT_LOCKED`). Per-IP lockout waits for the hosting and trusted-proxy decision.
- **Forgot and reset:** only an active employee of an active organization gets an email (`PASSWORD_RESET`, with the
  code as `resetCode`; no link yet). The code is 256 random bits, stored only as a hash, used once, valid for 30
  minutes, and replaces any earlier unused code. At most one email per account per 5 minutes and 5 per 24 hours
  (`peoplehub.auth.password-reset.*`, `PEOPLEHUB_AUTH_PASSWORD_RESET_TTL`, `_MIN_INTERVAL`, `_DAILY_LIMIT`). Every
  unusable code (unknown, expired, used, replaced, or its account no longer active) is the same 400 on `token`. A reset
  ends every session of the employee and clears the lockout.
- **Change:** needs the current password; a wrong one is a 400 on `currentPassword`. The session that made the change
  stays signed in; every other one ends.
- **Audit:** `PASSWORD_RESET_REQUESTED`, `PASSWORD_RESET_COMPLETED`, `PASSWORD_CHANGED` and `ACCOUNT_LOCKED`, with ids
  and counts only. Failed sign-ins stay in `login_attempt`. No "your password was changed" notification is sent yet.

### Signing keys

The application refuses to start without a valid key. Generate one per environment and never commit it:

```bash
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 | grep -v -- ----- | tr -d '\n'   # PEOPLEHUB_JWT_SIGNING_KEY
```

Give it an id (`PEOPLEHUB_JWT_SIGNING_KEY_ID`, for example `2026-09`). To rotate: generate a new key with a new id, put
the old key's **public** half (`openssl pkey -pubout`) in `PEOPLEHUB_JWT_PREVIOUS_PUBLIC_KEYS` as `oldid:PEM`, deploy,
and remove it again after 15 minutes (one access-token lifetime). Tests and `spring-boot:test-run` generate a throwaway
key themselves; `docker/smoke.sh` does too.

## Invitations

Employees and Admins never sign up; they are invited (b2-4; the decisions are "B2-4 decisions" in
[`CLAUDE.md`](CLAUDE.md)). Not built yet: MFA for invited Admins and promotion of an existing Employee (b2-7),
deactivation (b2-6), an invitation list (b3-3), bulk import (b3-5), notifications and rate limiting.

| Endpoint | Who | What it does |
|---|---|---|
| `POST /api/v1/admin/employees/invite` | Admin, Super Admin | Invites an Employee. Body `{name, email, employeeCode?, joinDate?}`. 201 with `{invitationId, employeeId, role, expiresAt}`. |
| `POST /api/v1/super-admin/admins/invite` | Super Admin | Invites a new person directly as Admin. Same body and answer. |
| `POST /api/v1/admin/invitations/{id}/resend` | Admin (Employee invitations), Super Admin | Replaces an open invitation with a new token, email and 7-day lifetime; also works on an expired one. |
| `POST /api/v1/admin/invitations/{id}/revoke` | Same | The token stops working at once. 204. |
| `GET /api/v1/public/invitations/{token}/preview` | Public | The organization, the role and the invitee's own name and email. Read-only. |
| `POST /api/v1/public/invitations/{token}/accept` | Public | Body `{password, confirmPassword}`: the invitee sets their own password and becomes active. 204; they then sign in normally. |

- **Inviting creates the employee** at once as `INVITED` with no password. An employee code is generated when none is
  given. An email that already belongs to the organization is a 409, except an `INVITED` person with no open invitation,
  who is simply invited again.
- **The organization is always the caller's own**, and the role is fixed by the endpoint; nothing in a request can
  change either, and the invitee cannot change them when accepting. Another organization's invitation id is a 404.
- **The token** is 256 random bits, emailed once (`EMPLOYEE_INVITED`, as `inviteCode`, with the organization login key
  and the role) and stored only as a hash. It is valid for 7 days (`peoplehub.invitation.ttl`, `PEOPLEHUB_INVITATION_TTL`)
  and can be accepted once. Any unusable token (unknown, expired, used, revoked) gets the same generic 404. No link is
  sent yet: the frontend's `/invite/{inviteCode}` page can be linked later from a frontend URL setting.
- The accepted password follows the same policy as registration and is stored only as an Argon2id hash. Every
  invitation action is audited (`INVITATION_CREATED`, `_RESENT`, `_REVOKED`, `_ACCEPTED`), with ids only.

## Logging & observability

**Logs** are one JSON object per line (Spring Boot's structured logging, `logstash` format) carrying `correlationId`
(the same id as the `X-Correlation-Id` header and the error body) and `actorId` (`anonymous` for requests until
authentication exists, then the employee id; `job:<name>` for scheduled jobs, which is already in place). Every API call
writes one line:

```json
{"@timestamp":"…","message":"HTTP request completed","level":"INFO","correlationId":"…","actorId":"anonymous",
 "method":"GET","route":"/api/v1/admin/employees/{id}","status":200,"durationMs":12}
```

- **No personal data.** The request log records the matched *route template*, never the raw path or query string
  (they can hold ids or approval tokens), and never headers, IP or bodies. Exception *messages* are never written
  (they can quote a value, for example a Postgres unique violation quotes the key): stack traces are logged as class
  names and frames only, Hibernate's own database-error logger is off, and Sentry events go through an allowlist
  scrubber. So do not put a value in a log message or an exception message and expect it to be scrubbed; log ids.
- **Levels** are INFO by default. Override per environment with `LOGGING_LEVEL_ROOT` / `LOGGING_LEVEL_COM_PEOPLEHUB`.
- **Local development.** `./mvnw spring-boot:test-run` activates the `local` profile (readable console, DEBUG for
  `com.peoplehub`). For your own run use `SPRING_PROFILES_ACTIVE=local`. Never use it outside a developer machine.

**Health** (Actuator; nothing else is exposed until authentication exists, and responses carry status only):

| Endpoint | Meaning | Use it for |
|---|---|---|
| `/actuator/health/liveness` | The process is alive. Ignores PostgreSQL and Redis. | Container health check / restart decisions |
| `/actuator/health/readiness` | Ready to serve: also checks PostgreSQL and Redis. | Load balancer / orchestrator traffic routing |
| `/actuator/health` | Full aggregate (status and group names). | Humans; not for restarts |

Liveness and readiness are separate on purpose: a brief database or Redis outage should stop traffic to an instance,
not restart it. (Spec 16.4 names `/actuator/health` for liveness; this is a recorded deviation, CLAUDE.md §15 item 9.)
Health checks are not written to the request log. Metrics are collected but not exposed yet.

**Graceful shutdown** is on: in-flight requests get up to `SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE` (default `30s`,
**unconfirmed**) to finish. Running jobs get the same timeout in a separate, later phase, so the orchestrator's stop
grace period must be **90s or more**, not merely above this value (see "Scheduled jobs" below for why).

**Sentry** is off unless `SENTRY_DSN` is set (also `SENTRY_ENVIRONMENT`, `SENTRY_RELEASE`). No tracing, no breadcrumbs.
Only `correlationId` and `actorId` tags, the exception type and frames and the log message template are sent.

## Scheduled jobs

Jobs use Spring's `@Scheduled` guarded by [ShedLock](https://github.com/lukas-krecan/ShedLock), so a job runs on one
instance at a time however many are started. The lock lives in PostgreSQL (`shedlock` table, migration V2) and uses the
database's clock. A job looks like this:

```java
@Component
public class ExampleJob {                        // non-final: ShedLock proxies the bean

    @Scheduled(fixedDelayString = "PT1M")
    @SchedulerLock(name = "example", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    public void run() {
        jobRunner.run("example", this::doWork);  // same name as the lock
    }
}
```

- **`JobRunner`** gives every run a fresh correlation id and the actor `job:<name>` in the logs, writes one start and one
  finish line with the duration, and logs a failure **once at ERROR** (that is what reaches Sentry, tagged with the job
  and correlation id). It does not rethrow, so one bad run never stops the schedule.
- **Idempotent and catch-up.** A job does not count on running once a day. It reads what is already done from the
  database and does everything that is due and missing, so after downtime the next run repairs the gap and running
  twice does no harm. There is no shared watermark table: each job derives its work from its own data. Every job ships a
  test proving it (see `DailyCloseExample` and `CatchUpPatternTest` for the shape).
- **Org timezone.** "Due" is decided in the organisation's timezone, inside the job. Schedule frequently and compute the
  day boundary from data; do not hard-code a cron zone.
- **`lockAtMostFor`** must be longer than the job can take: it is what frees the lock if an instance dies mid-run. The
  default when a job does not set one is `PEOPLEHUB_SCHEDULING_DEFAULT_LOCK_AT_MOST_FOR` (10 minutes).
- **Shutdown.** A running job gets up to `SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE` (30s by default, the same setting
  the web server uses) to finish; if it is still running then, it is **interrupted** and fails cleanly (logged and sent
  to Sentry like any failure), so write jobs to be interruptible and idempotent. Things that are easy to get wrong:
  `SPRING_TASK_SCHEDULING_SHUTDOWN_AWAIT_TERMINATION` must stay `false` (with `true` Spring ignores that timeout for
  jobs and abandons a still-running one while the database closes underneath it);
  `SPRING_TASK_SCHEDULING_SHUTDOWN_AWAIT_TERMINATION_PERIOD` (5s) is not a second wait for the job to finish but the
  time an *interrupted* job gets to unwind and release its lock before the database closes (set it to 0 and the lock
  can stay held until `lockAtMostFor`); and the web server and the scheduler stop **one after another**: a single slow
  request, or a single slow job, is bounded by its own 30s, but both together can take up to twice the timeout plus that
  period (**65s** by default). The orchestrator's stop grace period must therefore be **90s or more**.

The pool size, lock and shutdown defaults are unconfirmed (`[confirm]`). The `shedlock` table deliberately uses plain
UTC `timestamp` columns, the one exception to "`timestamptz` everywhere" (see the V2 header and CLAUDE.md §9).

## Audit log

`audit_log` (migration V3) is an **append-only** record of who did what (Spec 12, 15). Rows are only ever inserted.
Nothing calls the writer yet: it needs a real organization id, and organizations arrive with B2.

**Append-only is enforced twice, in the database.** The runtime role has no `UPDATE`, `DELETE` or `TRUNCATE` on the
table, and a statement-level trigger (`ENABLE ALWAYS`, so it also holds under `session_replication_role = replica`)
rejects all three for everyone, the table owner included. Accepted limit: a sufficiently privileged database owner or
superuser can still disable or drop the trigger; b0-6 does not defend against a malicious database administrator.

The runtime role may `SELECT` the table (a future Super Admin audit viewer reads it; there is no read endpoint yet) and
`INSERT` only the eight columns the writer supplies. It cannot supply `id` or `occurred_at`, which the database
generates (`occurred_at` is the database's `now()`, the transaction start time).

| Column | Type | Notes |
|---|---|---|
| `id` | `BIGINT` identity (`ALWAYS`) | database-generated |
| `organization_id` | `UUID NOT NULL` | no FK until B2; the nil UUID is rejected; never a placeholder |
| `actor_id` | `VARCHAR(64) NOT NULL` | the `ActorId` contract: employee id, `anonymous`, `SYSTEM`, `job:<name>` |
| `action` | `VARCHAR(64) NOT NULL` | `UPPER_SNAKE_CASE` |
| `target_type`, `target_id` | `VARCHAR(64)` | type is `UPPER_SNAKE_CASE`; id is a token; an id needs a type |
| `occurred_at` | `TIMESTAMPTZ NOT NULL` | `DEFAULT now()`, database time |
| `ip` | `INET` | only when there is a request |
| `correlation_id` | `VARCHAR(64)` | the `CorrelationId` contract |
| `details` | `JSONB NOT NULL` | the structure below, at most 4096 bytes |

### Writing an audit row

```java
@Transactional
public void changeRole(UUID organizationId, String employeeId, String from, String to) {
    // ... the change itself ...
    auditWriter.append(
            AuditEvent.builder(organizationId, "EMPLOYEE_ROLE_CHANGED")
                    .target(AuditTarget.of("EMPLOYEE", employeeId))
                    .details(AuditDetails.builder().change("role", from, to).changed("email").build())
                    .build());
}
```

- `AuditWriter.append` is the only operation. It joins the caller's transaction (`MANDATORY`): the audit row commits or
  rolls back with the change it describes, and calling it without a transaction is an error.
- The **actor** comes from `ActorId.current()` and the **correlation id** from `CorrelationId.current()`; the caller
  cannot pass either. No actor in the context means no row.
- The organization must be a **real** one from the authenticated principal: never null, never the nil UUID, never a
  made-up value to satisfy `NOT NULL`. An event with no resolved organization (for example a failed login for an unknown
  organization) does not belong in `audit_log`; it goes to security logging and rate-limit telemetry.
- **`details`** is built only through `AuditDetails`, from scalars, never from an object, map or entity:

  ```json
  {"v":1,
   "attributes":{"format":"CSV","rowCount":120},
   "changes":[{"field":"role","before":"EMPLOYEE","after":"ADMIN"},{"field":"email"}]}
  ```

  Keys and field names are `[a-z][A-Za-z0-9_]{0,39}`. String values are **tokens** (`[A-Za-z0-9._:-]{1,64}`: ids and
  codes, no spaces and no `@`, so no names or emails). At most 20 attributes and 20 changes. A name containing
  `password`, `secret`, `token`, `otp`, `recovery`, `credential` or `apikey` cannot carry a value; use
  `changed(field)`, which records that the field changed and nothing else. These limits are approved B0-6 defaults; a
  change is an explicit contract change.
- **Retention.** Immutable audit rows and the future retention/anonymization requirement (Spec 15) are in tension: the
  trigger blocks everything, so a retention job will need a deliberate owner-level mechanism. Not designed yet; tracked
  in CLAUDE.md.

## Email outbox

`email_outbox` (migrations V4, V5) is a **transactional outbox** (Spec 9.2): a queued email is
written in the same database transaction as the business change that caused it, so a crash cannot
lose it. One table serves as both the queue and the send history — its `status`/`attempts` columns
are the log, there is no separate `email_send_log`. Unlike `audit_log`, this table is deliberately
**mutable**: it has no append-only trigger.

### Writing (b1-1)

```java
@Transactional
public void inviteEmployee(UUID organizationId, String email, String inviteCode) {
    // ... the invite itself ...
    emailOutboxWriter.enqueue(
            EmailMessage.builder(organizationId, email, "EMPLOYEE_INVITED")
                    .payload(EmailPayload.builder()
                            .attribute("appName", "PeopleHub")
                            .attribute("inviteCode", inviteCode)
                            .build())
                    .build());
}
```

`EmailOutboxWriter.enqueue` is the only write operation: insert-only, `MANDATORY` propagation (same
shape as `AuditWriter`). `EmailPayload` is built only from scalars (same limits as `AuditDetails`:
20 attributes, 4096 bytes, tokens only, a secret-looking key cannot carry a value) — never an
object, map or entity. The organization must be real: never null, never the nil UUID, never a
placeholder. Nothing calls the writer with real data yet; organizations arrive with B2.

### Sending (b1-2)

`EmailOutboxProcessorJob` is a normal `@Scheduled` + `@SchedulerLock` job (`common/scheduling`'s
usual shape) wrapping the plain, testable `EmailOutboxProcessor`. Every `peoplehub.email.outbox.interval`
(default 30s, `[confirm]`) it:

1. Reclaims any row stuck in `SENDING` for longer than `peoplehub.email.outbox.stale-claim-after`
   (default 5 minutes) — only possible if a previous run's process crashed mid-send, since ShedLock
   already prevents two instances running this job at once.
2. Selects up to `peoplehub.email.outbox.batch-size` (default 100) due rows (`idx_email_outbox_due`).
3. **Claims** each with a conditional `UPDATE ... WHERE status IN ('PENDING','RETRYING')` *before*
   any network call, so the SMTP attempt never happens inside a held database transaction.
4. Renders the template, sends via `EmailSender`, and records the outcome.

**Templates** are classpath resources at `email-templates/<TYPE>.txt`: first line is the subject,
second line blank, the rest is the body, with minimal `{{key}}` substitution from the payload's
attributes — no templating engine dependency. An unknown `type` or a missing token is a permanent
failure (`TEMPLATE_ERROR`), never retried: it will not resolve itself.

**Retry/backoff** (Spec 9.2: "3 attempts over minutes"): `peoplehub.email.retry.delays`
(`PEOPLEHUB_EMAIL_RETRY_DELAYS`, default `PT1M,PT5M`) is an ordered list of delays; one initial
attempt plus one retry per delay, so the default is 3 attempts in total. The maximum is derived from
the list's length so it cannot drift from the schedule.

**Failure classification** (`EmailFailureClassifier`) is basic transient-vs-permanent, built only
from Jakarta Mail's/Spring Mail's own exception vocabulary — **never a provider-specific rule**.
Authentication failures and messages that could not even be prepared are permanent; a `MailSendException`
naming an invalid address is permanent; anything else (connection refused, timeout, an unrecognised
exception) is transient and gets a chance to retry. The `error` column only ever holds one of a
small closed set of codes (`EmailErrorCode`) — **never the raw exception text**, the same
message-free discipline `b0-4` established for logs.

**At-least-once delivery, not exactly-once.** If the process crashes after the SMTP call succeeds
but before the row is marked `SENT`, the row is reclaimed (step 1 above) and sent again on a later
run. This is a deliberate, accepted trade-off of the outbox pattern, not a defect: guaranteeing
delivery and guaranteeing no duplicate are not both achievable without a transactional inbox on the
receiving side.

### SMTP configuration

Plain SMTP via Spring's `JavaMailSender`, configured **only** through `spring.mail.*` properties —
`SmtpEmailSender` has exactly one code path and never branches on which provider it is talking to.

| Environment | `SPRING_MAIL_HOST` | `SPRING_MAIL_PORT` | Auth / STARTTLS |
|---|---|---|---|
| Local (`docker compose`, `./mvnw spring-boot:test-run`) | `mailpit` / `localhost` (default) | `1025` (default) | off |
| Production (initial target: Brevo's SMTP relay) | `smtp-relay.brevo.com` | `587` | on, via `SPRING_MAIL_USERNAME`/`SPRING_MAIL_PASSWORD`/`SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE` |

`spring.mail.host`/`port` default to Mailpit's usual local port so the `JavaMailSender` bean always
exists and every test and `spring-boot:test-run` keep needing no environment variables. There is no
Brevo SDK and no Brevo-specific code anywhere; switching provider is a configuration change only.

`peoplehub.email.from-address` (`PEOPLEHUB_EMAIL_FROM_ADDRESS`) is **required, with no default and
no fake placeholder sender** (Spec 9.2, "clear sender identity"): it is not declared in
`application.yml` at all, the same as `spring.datasource.*`, so an unset variable resolves to Java
`null` (never an empty string standing in for a real address) purely to let the application
*start* without it. `SmtpEmailSender` refuses to send while it is null or blank, marking the row
`FAILED` with `CONFIGURATION_ERROR` rather than crashing the process or silently using a placeholder
address.

**SMTP reachability is deliberately not a health check** (`management.health.mail.enabled=false`).
Adding `spring-boot-starter-mail` makes Spring Boot auto-register a mail health contributor the
moment a `JavaMailSender` bean exists, and the root `/actuator/health` endpoint aggregates *every*
registered contributor with no allowlist (unlike liveness/readiness, whose members are the explicit
list in the "Actuator" section above) -- so an unreachable SMTP server would otherwise drag the
whole aggregate to `DOWN`/503. That contradicts this project's own health design (D1): only
dependencies that should actually gate traffic or a restart are health-checked, and a temporarily
unreachable SMTP server is exactly the kind of transient failure `EmailOutboxProcessor`'s
retry/backoff already exists to absorb, not an application-health emergency.
`MailHealthDoesNotAffectOverallHealthTest` proves this against a deliberately unroutable address.

## Git workflow (Spec 16.2)

- Nobody commits to `main`. Every change is a branch, a pull request, then a squash merge.
- Branch names: `<type>/<phase>-<n>-<slug>`, with type one of `feature | fix | chore | docs | hotfix`,
  for example `feature/b0-1-skeleton-ci`. (Dependabot's `dependabot/...` branches are the accepted exception.)
- Commits follow [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `chore:`, ...).
- Migrations are forward-only; never edit an applied migration.

## CI

`.github/workflows/ci.yml` runs on every pull request and on pushes to `main`. Its job names are the checks to require
in branch protection:

| Check | What it does |
|---|---|
| `build` | `./mvnw verify`: compile, tests, Spotless format check (JDK 21) |
| `secret-scan` | gitleaks over the full git history |

SAST, dependency scanning, SBOM and the OpenAPI breaking-change check are added in later branches (Spec 16.2).

## Secrets

Never commit `.env` files, keys, keystores or credentials. `.gitignore` excludes them, and only `*.example` templates
are tracked. Configuration is supplied through environment variables.

### Scan for secrets locally (recommended)

Install [gitleaks](https://github.com/gitleaks/gitleaks#installing) and run it before you push:

```bash
gitleaks git --redact -v .                 # scan committed history
gitleaks dir --redact -v .                 # scan the working tree, including uncommitted files
```

**Pre-commit hook (planned):** Spec 15.12 calls for a gitleaks pre-commit hook. A hook is not installed by `git clone`
and needs gitleaks on each machine, so it will be added in a dedicated chore branch. Until then, CI is the enforced gate.
