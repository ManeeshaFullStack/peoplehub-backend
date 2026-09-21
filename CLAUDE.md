# CLAUDE.md — PeopleHub Backend (`peoplehub-backend`)

Persistent engineering rules for Claude in this repository. This file is a **condensed operating guide**;
`PROJECT_MASTER_SPEC.md` (**v9**, which supersedes v8) is the **single source of truth**. Section numbers below (e.g.
`§4.5`, `D9`) refer to it. v9 keeps the v8 numbering (§0–§22) and adds §2.1, §10.4, §12.1, §13.0, §15.1, §16.5 and
§22.1.

> **Two different `D#` series exist.** The spec has decisions D1–D30 (its §0). This repository also records its own
> implementation decisions as **"B0-4 decisions" D1–D9**, **"B0-5 decisions" S1–S7** and **"B0-6 decisions"
> B0-6/1–B0-6/16** (all in §2 below). A `D#` in the B0-4 block, or next to logging/health/Sentry/shutdown topics (for
> example "the D8 lifecycle timeout"), is the repository's; a `D#` next to a `§` reference or a product rule (for
> example D6 MFA, D7 tombstone, D8 email approvals, D9 no self-approval) is the spec's. The historical labels are
> deliberately not renumbered (owner decision). When a reference could be ambiguous, write the context explicitly:
> **"Spec D21"**, **"Spec D22"**, **"B0-4 D8"**, **"B0-5 S1"**, **"B0-6/4"**.

## 1. Source of truth & precedence

1. Read the relevant spec sections **before** planning any phase. Do not work from memory of the spec.
2. Precedence: **`PROJECT_MASTER_SPEC.md` > this file > your own defaults.** If they disagree, stop and tell the user.
3. **Never edit `PROJECT_MASTER_SPEC.md`** without the user's decision. Scope changes update the spec *first*, through a
   branch + PR (§16.2). If code and spec must diverge, that is a spec change, not a code decision.
4. **Spec conflict or ambiguity → stop and ask.** Do not silently pick a side. Known items are tracked in the readiness
   report; log new ones with section refs.
5. `[confirm]` defaults in the spec: implement them as **data-driven org settings** (§11) with the spec's default, never
   hardcode, and list them as unconfirmed in the phase report. Do not treat a `[confirm]` value as approved.
6. Open items (§19) are not blockers unless the phase touches them. If it does, ask.

## 2. Project snapshot

- **Multi-organization HR operations SaaS backend** (v9, D21): one deployment can host many organizations, and each one
  behaves as a completely private workspace (hard tenant isolation; see "v9 adoption" below). Scope: attendance
  (server-authoritative sessions), leave, approvals, org/departments, calendar, notifications, reports, audit.
  Standalone (own auth/org/data). **Not built yet:** the merged B0 code has no tenant model; organization
  registration, tenancy and login arrive in B2, so B0 must not pretend to implement them.
- **Stack:** Java 21, Spring Boot (modular monolith, **package-by-feature**), PostgreSQL (`timestamptz` everywhere,
  `btree_gist`), Flyway (forward-only), Redis (refresh-token families, rate limiting, presence, Spring Cache),
  Jakarta Validation, Logback JSON + MDC, `@Scheduled` + ShedLock, provider-agnostic `EmailService` + transactional
  outbox, SSE, springdoc OpenAPI, Sentry, Bucket4j, Apache POI, Lombok.
- **Testing stack:** JUnit 5, Mockito, Testcontainers, REST-assured. Build tool: **Maven via the wrapper (`./mvnw`)**
  (decided at initialization; no global `mvn`/`gradle` on the dev machine).
- **Data stack (b0-2):** PostgreSQL **17** (`postgres:17-alpine`) and Redis **7** (`redis:7-alpine`) for tests and local
  dev; Flyway 12, Hibernate 7, Lettuce, Testcontainers 2.x (all Boot-BOM-managed). Spring's standard env names
  configure connections (`SPRING_DATASOURCE_*`, `SPRING_DATA_REDIS_*`; see `.env.example`), with no custom placeholders
  in `application.yml`. Production hosting/versions are still an open item (§19).
- **Track:** backend B0 → B14, tagged `v1.0.0`, *then* frontend/agent/release. This repo only owns the backend track.
  Do **not** build frontend, agent, or infra-repo concerns here.
- **Repository (source of truth for the code):** `https://github.com/ManeeshaFullStack/peoplehub-backend`, a private
  repo on the owner's personal GitHub account. The history was **deliberately squashed** into one baseline commit
  (`a50ece8 Initial commit`) when the repo moved; the earlier history is not restored and no other repository path
  is to be referenced.
- Current status: **b0-1, b0-2 and b0-3 are merged; their code is in the baseline commit** (the individual merge
  commits no longer exist). **b0-4 is merged** (logging, request log, Sentry, health probes, graceful shutdown; PR #4).
  **b0-5 is merged** (ShedLock scheduler, `JobRunner`, V2 lock table; PR #6). **b0-6
  (`feature/b0-6-audit-log-append-only`: V3 `audit_log`, append-only trigger, two DB roles, `AuditWriter`) is implemented
  on its branch and awaiting review; it is not merged.** **b0-7 (`b0-7-docker-compose`) is not started** and is deferred
  (see §12). The master spec is **v9**; its own §16.5 checkpoint is stale for b0-4/b0-5 (see §15 item 10). Update this
  line when a phase merges.
- **Queued follow-ups (not yet scheduled):** (1) CI guard that fails when an already-merged migration file under
  `db/migration/` is modified or deleted (§16.2 "never edit an applied migration"); (2) gitleaks pre-commit hook
  (§15 item 12); (3) SAST, dependency scan, SBOM, **and the OpenAPI snapshot + breaking-change check** (§16.2) before B0
  closes (the OpenAPI check was deferred out of `b0-3` because there is no real API surface to compare yet); (4) the
  Dockerfile as its own branch; (5) **`CODEOWNERS`** (repository governance): not created because the owner's GitHub
  handle has not been supplied (B0-6/10); (6) **audit retention/anonymization** (below); (7) the **Super Admin
  audit-log read endpoint** (`GET /admin/audit-log`, B0-6/4) with its authorization and tenant isolation, a later phase;
  (8) the **hosting decision on Flyway credentials** (see "B0-6 decisions", B0-6/3).
- **Tracked design item — immutable audit history vs. retention/anonymization.** `audit_log` is append-only and its
  trigger blocks every UPDATE/DELETE/TRUNCATE, while the spec (§15, "Data retention") requires a written retention
  policy and configurable retention/anonymization before go-live, and D7 tombstoning keeps audit rows. b0-6 does not
  design retention or anonymization (B0-6/8, owner decision). A future job that must change or remove audit rows needs a
  deliberate, separately approved owner-level mechanism; it must not be worked around by loosening the trigger or the
  grants. Decide it with the retention policy, before go-live.
- **Exception messages can contain personal data** (for example a Postgres unique-violation message includes the
  offending key value). Done in `b0-4` at the logging layer: message-free throwable rendering, a Sentry allowlist
  scrubber, and Hibernate's own error-text logger switched off (see "B0-4 decisions" below, D5/D6).
  **Owed from B4 onward (tracked, D6):** domain services (attendance session overlaps, leave overlaps, duplicate
  unique keys, and so on) must translate *expected* conflicts into `ApiProblemException` with the correct **409** and
  a safe `detail`. Until then an unexpected DB integrity violation is a generic 500 with message-free logging. That
  is a stop-gap, **not** the permanent behaviour: do not add a blanket `DataIntegrityViolationException` -> 409
  handler (it would hide real bugs), and do not let 500 quietly become the answer for a conflict a user can cause.
  Also owed later: 401/403 responses must use the same problem body (B2 Security entry points); `Idempotency-Key`
  handling (B4).
- **Open decision item (raised in `b0-4`, no code change today): log timestamp zone.** Structured log timestamps use
  the JVM's default zone offset (for example `+05:30` on a developer machine), not forced UTC. It is an unambiguous
  ISO-8601 instant, so nothing breaks, and a container's `TZ` will likely make it UTC and the question moot. Owner
  decision: do **not** add a custom formatter in `b0-4`; revisit when containerizing (`b0-7` / §16.4) and either
  confirm the container runs in UTC or force UTC then.
- **Open decision item (raised in `b0-4`): Sentry scrubber is a denylist by name for top-level fields.**
  `SentryEventScrubber` allowlists tags and contexts but clears the other data-carrying event fields by name, so a
  new top-level field added by a future SDK version would not be cleared automatically. Owner decision: leave as is
  for `b0-4` (do not rebuild it into a true allowlist now). **Re-check the class every time `sentry.version` is
  bumped**; if it is still wanted then, rebuild the event from permitted fields only, with a test that fails if any
  unlisted field survives.
- **Owner decisions (initialization):** Maven + Maven Wrapper (not Gradle). `PROJECT_MASTER_SPEC.md` stays in this
  repo for the current implementation phase (not moved to `peoplehub-docs`) and remains the source of truth.
  `.gitignore`, `CLAUDE.md`, `PROJECT_MASTER_SPEC.md`, `README.md` and `doc/` (when it exists) are tracked project
  files and must **never** be added to `.gitignore`. `b0-7` is deferred (see §12); it does not block `b0-1`…`b0-6`.
  **Never invent or guess the owner's GitHub handle.** Do not create `CODEOWNERS` until the owner supplies the handle
  or a phase requires it (auth/migration/security paths, §16.2). Spec inconsistencies are recorded in §15 and flagged
  to the owner before the affected phase; the spec itself is not edited.

### v9 adoption — multi-organization SaaS (FUTURE-PHASE requirements; none of this is built or to be retrofitted into B0)

v9 (spec §0 D21–D30, §2.1, §3.3, §8.2, §12.1, §13.0, §15.1, §22.1) changes the product from "one deployment = one
company" to a multi-organization SaaS. It lands **primarily in B2** (`b2-1-org-tenant-employee-schema` …
`b2-8-tenant-isolation-security-tests`, spec §17), in B1 (email/verification/invite mail) and in F1/security work, and
must not be pulled into B0. The requirements to design towards:

- **Tenant boundary (D21, §2.1.1):** every authenticated principal is bound to exactly one `organization_id`
  (immutable internal id; the public **organization login key** is a separate, unique, normalized value). Tenant scope
  comes **only** from the authenticated principal (JWT carries `organization_id`, employee id, role), never from a
  body/query/path value after login.
- **Absolute isolation (D22, §2.1.2, §15.1):** no tenant discovery, listing, counting, searching, or inference of other
  organizations or their users/data. A cross-tenant object id is **not found** inside the caller's tenant, never
  "forbidden because it belongs to another company". Admin and Super Admin power is organization-local and never
  crosses the boundary. Every tenant-owned query, mutation, cache key, event, export, SSE stream, audit row and
  background job is scoped by the organization id; no unrestricted `findById` for tenant-owned aggregates in request
  paths.
- **Defense in depth (D30, §12.1):** mandatory application-layer scoping **and** PostgreSQL RLS (or an equally strong
  DB-level policy proven by tests) on tenant-owned tables, with tenant-safe composite keys where practical (for example
  `UNIQUE(organization_id, email_normalized)`). RLS needs a non-owner runtime role, which is consistent with the
  two-DB-role design in §9. This is a **future implementation requirement**, not something to bolt on early.
- **Registration / bootstrap (D23, D29, §2.1.3):** only an organization **founder** self-registers (public
  `/register`). Organization + founder are created atomically; the founder is an individual (own company email and
  private password, no shared "superadmin" credentials) and becomes the first `SUPER_ADMIN`. Flow: email verification ->
  **mandatory MFA** (TOTP + recovery codes) -> first-time organization setup -> the real Super Admin dashboard. No
  normal session exists before verification; responses are non-enumerating.
- **Invitation-only membership (D24, D25, §2.1.5, §3.3):** Employees and additional Admins never sign up publicly. They
  arrive by single-use, expiring email invitations and set their own passwords privately. A Super Admin may promote an
  existing active Employee (step-up protected) **or** invite a new person directly as `ADMIN`. An Admin or Super Admin
  is always an Employee record (`role` is a relation, not a separate identity).
- **Login (D27, §2.1.6, §8.2):** every role uses **Organization + company email + password**; there is no role
  selector. The server resolves the tenant, authenticates inside it, then routes by the stored role. Failure is one
  generic message with timing and rate-limit protection.
- **MFA (D6, §8.3):** mandatory for Admin and Super Admin (including a directly invited Admin, before first workspace
  access); optional for Employees.
- **Deactivation (D26, §2.1.7):** immediately blocks login, refresh, API and SSE access, revokes refresh-token families
  and sessions and device authorization, reassigns pending approvals and closes an open session (`DEACTIVATION`), while
  preserving attendance, leave, approval and audit history. Normal exit is deactivation, not hard delete; D7 tombstoning
  stays a separate step-up operation.

What this means for the merged B0 work: nothing is rewritten. Design questions it raised, and open ones for later
branches:

- **`b0-6` audit table: B0-6 design resolved; B2 completion pending** (see §15 item 13 and "B0-6 decisions"). It is
  `audit_log.organization_id UUID NOT NULL` with **no FK**, and no writer may ever invent a value to satisfy `NOT NULL`.
  B2 still owes: the `organization` table, a matching primary-key type (UUID), the FK from `audit_log`, tenant RLS or
  an equivalent DB defence in depth, and cross-tenant integration tests.
- **Tenant-owned scheduled jobs (B4+).** A job that touches tenant-owned data must carry the organization id
  explicitly and compute "due" in that organization's timezone. Whether ShedLock locks are per job or per tenant is
  **unresolved** and is decided when the first such job (the B4 day split) is built; the B0-5 mechanism supports either.
- **Logging context (B2).** Spec §15.1 allows an internal organization id in operational logs (never exposed across
  tenants). **Unresolved until B2 introduces tenant context:** if it is added to MDC, the Sentry scrubber's tag
  allowlist must be extended deliberately (it currently allows only `correlationId` and `actorId`).

### B0-4 decisions (owner-approved 2026-09-21; recorded here so they survive a session or repo reset)

- **D1 — Health endpoints.** Split into `/actuator/health/liveness` and `/actuator/health/readiness`. The Dockerfile
  health check uses **liveness**. This deviates from the literal spec path (§16.4 names `/actuator/health` for
  liveness) on purpose: liveness must not depend on PostgreSQL or Redis, so a DB/Redis blip makes the instance
  *not ready* (traffic stops) without getting the container *restarted*. Readiness also verifies DB and Redis. The root
  `/actuator/health` is the full aggregate (status + group names only); do not use it for restarts. Logged as
  §15 item 9.
- **D2 — JSON logging.** Spring Boot's built-in structured logging (`logging.structured.format.console=logstash`,
  Logback). No `logstash-logback-encoder` dependency.
- **D3 — Request log path.** Log the matched **route template** (`/api/v1/admin/employees/{id}`), never the raw path or
  the query string. Unmatched requests log `UNMATCHED`. No headers, IP or body are logged.
- **D4 — Actor id before auth.** `ActorIdFilter` seeds `anonymous` now; B2's authentication step wires the real employee
  id via `ActorId.set(...)`, and scheduled jobs use `SYSTEM` or the job name. The filter owns clearing the MDC.
- **D5 — Sentry scrubbing.** `SentryEventScrubber`: tags and contexts are a true allowlist; every other data-carrying
  field is cleared by name, so **re-check the class whenever `sentry.version` is bumped** (a new top-level SDK field
  would not be cleared automatically). Keep exception type/module/frames, the log message
  *template*, `correlationId` and `actorId` tags, level/release/environment. Drop exception messages, formatted
  message and arguments, request, user, breadcrumbs (`max-breadcrumbs: 0`), extras, server name, transaction name and
  every context except runtime/os/spring. Sentry is **disabled when `SENTRY_DSN` is empty**; no tracing.
- **D6 — DB integrity violations.** Generic 500 with message-free logging for now (no blanket 409). Tracked follow-up
  from B4 onward: see the "Owed from B4 onward" item above. Also turns off Hibernate 7's `org.hibernate.orm.jdbc.error`
  logger, which writes the database's error text (including the offending key) as a log *message*.
- **D7 — Metrics.** Micrometer collects metrics; `/actuator/metrics` (and every endpoint except `health`) stays
  **unexposed until B2 auth exists**. Revisit with authentication, or on a separate management port.
- **D8 — Graceful shutdown.** `server.shutdown=graceful`, `spring.lifecycle.timeout-per-shutdown-phase=30s`,
  overridable with `SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE`. **`[confirm]`: 30s is an unconfirmed default**; flag it
  as such in the PR description. It applies to the web-server phase and, separately, to the scheduler phase, so shutdown
  can take up to 65s: the orchestrator's stop grace period must be **90s or more** (see "B0-5 decisions" and §12).
- **D9 — Docs.** README (logging & observability section) and `.env.example` are updated in the `b0-4` branch.

### B0-5 decisions (owner-approved 2026-09-21; recorded here so they survive a session or repo reset)

- **S1 — Lock store.** PostgreSQL via ShedLock's JDBC provider (`shedlock` table, migration V2). No Redis dependency for
  scheduling: a Redis flush or outage cannot cause a double run or stall jobs.
- **S2 — Column types.** Try `timestamptz` first and verify empirically; pre-approved fallback to ShedLock's plain
  `timestamp` (UTC) if not deterministic across JVM/session zones, recorded as an explicit exception. **The fallback
  was needed and is in place**: see the exception bullet in §9 (measured evidence, guard test). It is the only
  exception to "`timestamptz` everywhere".
- **S3 — Lock clock.** `usingDbTime()`: the database's clock is the shared clock, so instance clock skew cannot make
  two instances believe they hold a lock.
- **S4 — Actor id.** Job log lines and Sentry events carry `actorId=job:<name>` (a fresh `correlationId` per run).
- **S5 — Catch-up.** No generic watermark/`job_run` table. Each job derives its work from persisted state and decides
  "due" in the org timezone, with a documented pattern (`DailyCloseExample`) and a proof test **per job going forward**
  (after simulated downtime the next run repairs the gap; a repeat run changes nothing).
- **S6 — `JobRunner`.** Explicit `jobRunner.run("<name>", () -> …)` inside the `@Scheduled` method; no annotation or AOP
  magic.
- **S7 — Defaults, all env-overridable and `[confirm]` (unconfirmed, flag in the PR):** `lockAtMostFor` 10 minutes
  (`PEOPLEHUB_SCHEDULING_DEFAULT_LOCK_AT_MOST_FOR`), scheduler pool size 4 (`SPRING_TASK_SCHEDULING_POOL_SIZE`), interrupted-job
  unwind period 5s (`SPRING_TASK_SCHEDULING_SHUTDOWN_AWAIT_TERMINATION_PERIOD`, see below). The
  30s job shutdown wait is the D8 lifecycle timeout (below), not a separate setting.
- **Shutdown behaviour of jobs (measured; owner-approved Option A, with one measured amendment).**
  `spring.task.scheduling.shutdown.await-termination` is **`false`**. A running job gets up to
  `spring.lifecycle.timeout-per-shutdown-phase` (30s, D8) to finish; if it is still running then it is **interrupted**
  and fails cleanly (one ERROR, lock released). Findings that must not be forgotten:
  - `await-termination` and the lifecycle timeout are **alternatives, not additive**. With `await-termination=true`
    Spring ignores the lifecycle timeout for jobs, waits only `await-termination-period`, then *abandons* a
    still-running job: it keeps running while the DB pool closes under it and its lock is never released. Do not flip it
    back. (An earlier draft of this repo's docs claimed the waits "add up"; that was wrong and was corrected before
    merge.)
  - **Amendment to "drop `await-termination-period` entirely":** it is kept at **`5s` `[confirm]`**. With `false`,
    Spring interrupts the job but does not wait for it to unwind unless a period is set, so the DB pool can close
    while the job is still releasing its lock; the unlock then fails and the lock stays held until `lockAtMostFor`
    (10 minutes by default). Observed in a real log ("Unexpected error occurred in scheduled task" 14 ms after the pool
    shut down) and made deterministic in `ScheduledJobInstancesTest` with a probe job that needs 1.5s to unwind:
    period 5s passes, period 0 fails. This period is **unwind time for an interrupted job, not a second wait for the job
    to finish.** 5s unwind period approved on 2026-09-21 as the current default; retain `[confirm]` for
    production/configuration review (deployment-time confirmation of the configurable defaults is still wanted).
  - Shutdown phases run **sequentially**: the web server (slow requests), then the scheduler (slow jobs), each with the
    full timeout. Typical worst case is one timeout (30s); a slow request **and** a slow job together take up to two,
    plus the unwind period: **65s true worst case** with the defaults (60s + 5s).
  - Jobs must therefore be **interruptible and idempotent**: an interrupted job's work is picked up by the next run
    (catch-up, S5).
  - Proven by `ScheduledJobInstancesTest` (in-flight job finishes; an overrunning job is interrupted and its lock
    released), each with negative controls (`await-termination=true` and period `0` both fail it).
- **REQUIRED in `b0-7` (and wherever any orchestrator/Docker Compose config is ever added): stop grace period 90s.**
  `terminationGracePeriodSeconds` (Kubernetes) / `stop_grace_period` (Docker Compose) / the equivalent must be set to
  **90s or more**, comfortably above the 65s worst case (60s + 5s unwind), never left at the orchestrator's default (Docker Compose 10s,
  Kubernetes 30s: both would kill the container mid-shutdown). If `spring.lifecycle.timeout-per-shutdown-phase` is
  changed, the grace period must stay above twice it plus margin. Re-check this at the start of `b0-7` and again when
  the production hosting decision (§19) is made; it is also recorded in §12.
- **`shedlock.locked_by` holds the instance's hostname** (owner-approved, leave as is): a container id in production, a
  machine name on a developer's machine. It never leaves the database and is not logged. No explicit instance id.
- **Not in `b0-5`:** the real jobs (day split B4, accrual B9, comp-off expiry B10, retention B13), org settings (B2),
  and Sentry alert *rules* (configured in Sentry, not in code; the code guarantees a failed job reaches Sentry tagged
  with its job and correlation id).

### B0-6 decisions (owner-approved 2026-09-21; recorded here so they survive a session or repo reset)

Cite as "B0-6/4" and so on (never a bare `D#`, which is the spec's or B0-4's). Code: `audit/` (`AuditWriter`,
`AuditEvent`, `AuditTarget`, `AuditDetails`), `common/database/RuntimeRolePlaceholderGuard`; migration
`V3__audit_log.sql`.

- **B0-6/1 — `organization_id`.** `audit_log.organization_id UUID NOT NULL`, **no FK** (there is no `organization`
  table until B2; `b2-1` is not pulled forward). **No fake, sentinel, placeholder or fabricated organization UUID may
  ever be written to satisfy `NOT NULL`.** The writer refuses null and the nil UUID (and a CHECK rejects the nil UUID),
  and until B2 introduces real organizations no application path may write an audit row, so no production code calls
  `AuditWriter` yet (acceptable). UUID is the organization identifier type going forward. B2 adds the FK and tenant
  RLS/defence in depth. Recorded as **"B0-6 design resolved; B2 completion pending"** (§15 item 13), not "resolved".
- **B0-6/2 — Append-only, defence in depth.** (1) The runtime role has no `UPDATE`, `DELETE` or `TRUNCATE`. (2) One
  statement-level trigger `trg_audit_log_append_only` (`BEFORE UPDATE OR DELETE OR TRUNCATE`, function
  `audit_log_reject_change`, message without row data) is `ENABLE ALWAYS`, so it also fires under
  `session_replication_role = replica`. Statement-level so it rejects even a zero-row UPDATE/DELETE. **Accepted trust
  boundary:** a sufficiently privileged database owner or superuser can alter, drop or disable these protections; b0-6
  does not defend against a malicious database administrator and does not try to make the table cryptographically
  immutable. Tests prove the application/runtime boundary.
- **B0-6/3 — Two roles.** Migration/owner role (Flyway: `SPRING_FLYWAY_USER`, `SPRING_FLYWAY_PASSWORD`, optional
  `SPRING_FLYWAY_URL`) and least-privilege runtime role (`SPRING_DATASOURCE_*`, name in `PEOPLEHUB_DB_RUNTIME_ROLE`,
  default `peoplehub_app`). **Flyway never creates roles** (`CREATE ROLE` needs elevated privileges): infrastructure
  provisions both before the first start (README, "Database roles"); tests provision them with
  `src/test/resources/testcontainers/db-roles.sql`. Existing tests keep the container's superuser for both Flyway and the
  app (smallest change); only the privilege-boundary tests connect as the roles. Anything that starts its own
  PostgreSQL test container must use `TestcontainersConfiguration.newPostgresContainer()` (that is why
  `ScheduledJobInstancesTest` changed). **Residual risk, tracked for `b0-7` and hosting (§19):** Flyway runs in the app
  process, so the app's environment holds the owner credentials; the stronger setup is a separate migration job with
  `SPRING_FLYWAY_ENABLED=false` on the app. The V3 fail-fast check is approved: a missing runtime role fails the
  migration with a readable message and rolls it back.
- **B0-6/4 — Runtime may `SELECT` and `INSERT`.** v9 says the audit table is "write-only for the app" (spec §15
  security checklist, item 13; not this file's §15), but it also requires a future Super Admin audit-log viewer, so
  **`SELECT` is required for that authorized backend read path**. The runtime role has `SELECT` and `INSERT` only, never `UPDATE`, `DELETE` or
  `TRUNCATE`. `GET /admin/audit-log` is **not** built in `b0-6`; its authorization and tenant isolation are future work.
- **B0-6/5 — Actor.** `actor_id VARCHAR(64) NOT NULL` following the `ActorId` contract (`[A-Za-z0-9._:-]{1,64}`:
  employee id, `anonymous`, `SYSTEM`, `job:<name>`). Not UUID-only, no actor FK. A CHECK mirrors the pattern and
  `AuditLogMigrationTest` keeps Java and SQL in step. `correlation_id VARCHAR(64)` follows `CorrelationId` the same way.
- **B0-6/6 — Schema.** `id BIGINT GENERATED ALWAYS AS IDENTITY` PK; `organization_id UUID NOT NULL`; `actor_id`;
  `action`, `target_type` (UPPER_SNAKE_CASE, at most 64); `target_id` (token, needs a type); `occurred_at TIMESTAMPTZ NOT
  NULL DEFAULT now()`; `ip INET`; `correlation_id`; `details JSONB NOT NULL`. `occurred_at` replaces the spec's
  `timestamp` (avoids the ambiguous name; same meaning). `correlation_id` and `organization_id` come from v9 §12.1 (the
  §12 list is the stale one). No secondary indexes in b0-6 (B0-6/15).
- **B0-6/7 — Time.** `occurred_at` is the database's `now()` (transaction start time; rows in one transaction share it
  and `id` orders them). The application never generates it; the `Instant.now()` rule stands. The runtime role cannot
  supply it or `id` (B0-6/12).
- **B0-6/8 — Metadata safety; no retention design.** No secrets, passwords, tokens, OTPs, recovery codes or unnecessary
  raw PII in `details`. It is structured and deliberately supplied (B0-6/11), never a serialized domain or request
  object. Retention/anonymization jobs are **not** designed in b0-6; the tension with an append-only table is a tracked
  future design item (the "Tracked design item — immutable audit history vs. retention/anonymization" bullet above).
- **B0-6/9 — Java scope.** A small insert-only writer: `AuditWriter.append(AuditEvent)`, annotated
  `@Transactional(propagation = MANDATORY)` (the audit row commits or rolls back with the change it describes). Actor
  from `ActorId.current()` (absent means no row), correlation id from `CorrelationId.current()` (optional), organization
  required, id and time never supplied, plain JDBC (no entity or repository), no update/delete/read methods. Not built:
  the read endpoint, auth, employees, organizations or any other B2 work.
- **B0-6/10 — `CODEOWNERS`.** Not created (owner handle not supplied); a governance follow-up.
- **B0-6/11 — `details` is one versioned JSONB object** `{"v":1,"attributes":{…},"changes":[{"field","before","after"}]}`
  (not separate before/after snapshot columns: those invite whole-entity dumps and PII). `changed(field)` records that a
  sensitive field changed with no value. Built only through `AuditDetails` from scalars (`String`, `long`, `boolean`);
  no `Object`, `Map`, POJO or entity crosses the public API. Approved limits, **not `[confirm]`** and revisable only
  through an explicit contract change: at most 20 attributes, 20 changes, 4096 serialized bytes (also a CHECK), string
  tokens at most 64 characters (`[A-Za-z0-9._:-]`, so no spaces or `@`), actions and target types `UPPER_SNAKE_CASE` at
  most 64. A key or field whose name contains `password`, `secret`, `token`, `otp`, `recovery`, `credential` or
  `apikey` cannot carry a value (a backstop; the control is the typed API).
- **B0-6/12 — Column-level `INSERT`.** The runtime role may insert only `organization_id, actor_id, action, target_type,
  target_id, ip, correlation_id, details`, **not `id` or `occurred_at`**, so identity and audit time are
  database-generated by enforcement, not convention.
- **B0-6/13 — `shedlock` privileges (measured).** The runtime role has `SELECT, INSERT, UPDATE` on `shedlock` and **no
  `DELETE`**. `ShedLockRuntimeRoleTest` removes each privilege in turn and ShedLock fails every time: `SELECT` is needed
  too because PostgreSQL requires it for an `UPDATE … WHERE`. This corrects the earlier note that only INSERT and UPDATE
  were needed.
- **B0-6/14 — Runtime role name is never trusted SQL.** Flyway substitutes placeholders as text before the database
  sees the SQL, and cannot validate them, so the value is validated first: `RuntimeRolePlaceholderGuard` (a
  `FlywayConfigurationCustomizer`) refuses to start the application unless `spring.flyway.placeholders.runtime_role`
  (from `PEOPLEHUB_DB_RUNTIME_ROLE`, default `peoplehub_app`) matches `[a-z_][a-z0-9_]{0,62}`, before Flyway touches the
  database. `V3` re-checks it with the same pattern, confirms the role exists, and uses it only through `format('%I')`
  in dynamic `GRANT`s. Never build SQL from an unchecked environment value. Only a run that bypasses the application
  (Flyway's own CLI with a hand-made placeholder) relies on the migration's own check alone.
- **B0-6/15 — No secondary indexes** on `audit_log`; the primary key is enough now. The future reader's tenant-scoped
  access patterns decide the indexes when it is built.
- **B0-6/16 — No tenant, no audit row.** An event with no legitimately resolved organization (for example a failed login
  for an unknown organization) cannot be inserted into tenant-scoped `audit_log`; do not fabricate a tenant. It belongs
  in security logging and rate-limit telemetry until a future, explicitly designed model says otherwise (also what
  v9's non-enumeration rule wants).

## 3. Workflow — every task

**Investigate → Plan → Implement → Test → Verify → Review → Report.** Never skip Test or Verify.

- **Investigate:** read spec sections, existing code, migrations, and tests. State what exists vs. what is missing.
- **Plan:** short, concrete; name files, migrations, endpoints, tests. Small change per branch (§16.2). For anything
  non-trivial or with a design choice, present the plan before writing code.
- **Implement:** smallest change that satisfies the spec section. No speculative features, no scope creep, no
  refactors of unrelated code.
- **Test:** write tests with the code (see §8). Run them. Report real output.
- **Verify:** run the build + full relevant test suite; for runtime behavior, actually start the app / containers and
  exercise the endpoint. "Compiles" is not verification.
- **Review:** self-review the diff against the PR checklist (§10) before reporting.
- **Report:** what changed, what was tested (with results), what was *not* tested, deviations, open questions. State
  failures plainly; never claim done when tests fail or steps were skipped.

Make normal engineering decisions yourself. **Ask the user only when:** the spec marks something for confirmation; a real
product/business decision is needed; the spec conflicts with itself or reality; an action is destructive/irreversible;
shared/production infrastructure is involved; or a Git action needs approval.

## 4. Git rules

- **Never work on `main`.** Never push to it. Every change — including docs and hotfixes — is branch → PR → merge.
- **Branch naming:** `<type>/<phase>-<n>-<slug>`, type ∈ `feature | fix | chore | docs | hotfix`, e.g.
  `feature/b0-3-api-standards-pagination-openapi`. Use the branch slugs from §17 for each phase. One small,
  reviewable change per branch.
- **Commits:** Conventional Commits (`feat:`, `fix:`, `chore:`, `docs:`, `test:`, `refactor:`).
- **Do not commit, push, open/merge PRs, tag, or deploy without the user's explicit approval for that action.**
  Approval for one action does not extend to the next. Never force-push, never `--no-verify`, never bypass hooks/signing.
- Never commit secrets, `.env`, keys, or generated output. Check `git status` before any commit approval request.
- Migrations: **forward-only**; never edit an applied migration; destructive changes use expand/contract (§16.2).
- Branch protection on `main` is a GitHub setting the **user** must apply (§16.2) — remind them; do not attempt it.
- Releases: SemVer tags per repo; `main` → staging; production only from a tag with manual approval. Claude never
  deploys.

## 5. Architecture rules

- **Package-by-feature** modular monolith (e.g. `attendance`, `leave`, `approval`, `employee`, `department`,
  `calendar`, `notification`, `security`, `audit`, `common`). Modules talk through service interfaces/events, not by
  reaching into each other's repositories.
- **Layering:** controller (HTTP + validation only) → service (business rules, **authorization**, transactions) →
  repository. Entities never leave the service layer; expose DTOs. No business logic in controllers.
- **Authorization lives in the service layer**, not only controllers (§3.2). Self-service endpoints take the employee id
  **from the token only** — never from a path/query/body param (IDOR). The department roster endpoint never accepts a
  caller-supplied department id (§3.1). Admin can never modify a Super Admin. **Nobody approves or edits their own
  leave/attendance (D9).** *From B2 (v9):* every tenant-owned lookup is tenant-qualified with the organization taken
  from the authenticated principal, and another tenant's id is "not found" (§2.1.2, §15.1); see "v9 adoption" in §2.
- **Time:** inject a `Clock`; never call `Instant.now()`/`LocalDate.now()` directly. Store UTC `timestamptz`; compute
  durations from instants in **seconds**; day boundaries use the **organization's timezone** (`organization.timezone`,
  §4.2, §11, §12.1). All time logic must be testable with a fixed/advancing clock.
- **Server is the only clock (R5).** Attendance timestamps are server-set. The only client-influenced times are the
  bounded offline check-out (§4.6) and signed agent events (§4.4), both flagged.
- **Attendance golden rules R1–R6 are non-negotiable** (§4.1): only manual check-out, verified shutdown, estimated
  shutdown, deactivation, admin edit/approved correction, or the safety cap end a session; midnight *splits*, never
  ends. Rest counts (D1). Presence never affects hours or auth (§8.4).
- **Caching (D15/§13.3):** cache-aside via Spring Cache/Redis **only** for org settings, leave types, department list,
  calendar ranges; 5–15 min TTL + explicit `@CacheEvict` on every write; keys namespaced `org:{organizationId}:…`
  (spec §12.1; §13.3 writes `orgId`, same thing) so tenants can never share a cache entry. **Never cache** open
  sessions, `attendance_day`, leave balances/ledger, the live dashboard, or anything mid-approval.
- **Scheduled jobs:** `@Scheduled` + ShedLock, idempotent, catch-up after downtime, org-timezone day logic, failures
  alert (never silent). **As built in `b0-5`** (`common/scheduling`, decisions S1–S7 in §2): a job is a non-final bean
  with `@Scheduled` + `@SchedulerLock` whose body calls `jobRunner.run("<name>", …)` (name = lock name = actor
  `job:<name>`); the job derives its work from persisted state and decides "due" in the org timezone, so a run after
  downtime repairs the gap and a repeat run is a no-op; every job ships a proof test of that. No generic watermark
  table. Failures are logged once at ERROR by `JobRunner` (that is the Sentry alert), never rethrown, never silent.
  *Multi-tenant (v9, future):* a job that touches tenant-owned data carries the organization id explicitly and computes
  "due" in that organization's timezone; lock granularity (per job or per tenant) is decided with the first such job.
- **Email:** always via the transactional **outbox**, committed in the same transaction as the business change (§9.2).
- Lombok: `@Getter/@Setter/@Builder` on entities; **avoid `@Data` on JPA entities** (broken `equals/hashCode/toString`
  with lazy associations). `@Data`/records are fine for DTOs and value objects. The annotation-processor dependency
  must be in the build regardless of the IDE plugin (§21).
- No hardcoded UI strings/org values: `APP_NAME` and all policy values are config/settings (§0, §11).

## 6. API standards (§13) — apply to every endpoint

- **Base path `/api/v1` is applied automatically** to every `@RestController` under `com.peoplehub` (`WebConfig`).
  Controllers declare only their own path (`@RequestMapping("/admin/employees")`); never write `/api/v1` yourself.
  OpenAPI is generated via springdoc (docs at `/v3/api-docs`; Swagger UI is **off** unless
  `SPRINGDOC_SWAGGER_UI_ENABLED=true`). **Every endpoint documented**; CI must fail on breaking changes (queued).
- **Every list endpoint** takes a `PageQuery` parameter annotated with `@PageParams(defaultSize, defaultSort,
  sortable)` and returns `PageResponse<T>` = `{ items, page, size, totalElements, totalPages }`. **Never a bare array,
  never Spring's `Page` in a response.** `page` is 0-based, `size` default 20 and **max 100 (larger is a 400, not a
  clamp; use the export)**, `sort=field,dir` repeatable. Unknown/repeated/malformed sort or bad page/size is a 400 that
  lists every problem (`fieldErrors`) and, for sort, `allowedSortFields`. Sort names must match entity property names.
  A page past the end is 200 with empty `items`. Export formats are the only unpaginated responses (streamed).
- **Every write endpoint** validates via Jakarta Bean Validation on the DTO, plus custom/service-level rules for
  cross-field and business checks. **All errors are one RFC 9457 (7807) `application/problem+json` body**: `type`
  (a `urn:peoplehub:problem:*` from `ProblemType`), `title`, `status`, `detail`, `instance`, `correlationId`, plus
  `fieldErrors` (`field`, `message`) for validation. Produced only by `GlobalExceptionHandler`. To signal an error,
  throw `ApiProblemException` (add a `ProblemType` entry first); never build your own error response or shape.
- **Error bodies must never leak:** no stack traces or exception text on 5xx (generic detail + correlation id, cause is
  logged), no echoing of rejected values, and `instance` is `urn:peoplehub:request:<correlationId>` — **never the request
  path or query string** (paths can hold ids or email-approval tokens; Spring would otherwise fill in the raw path).
- Every response carries `X-Correlation-Id` (propagated from the caller only if it matches `[A-Za-z0-9._-]{1,64}`,
  else generated) and it is in MDC as `correlationId`. `Idempotency-Key` on state-changing POSTs (B4); ISO-8601 UTC
  instants (Jackson 3 default, locked by a test); consistent generic auth errors (no enumeration).
- **Tenant contract (from B2, spec §13.0):** every authenticated endpoint ignores or rejects any client attempt to
  select an `organization_id`; tenant scope comes only from the authenticated principal. Public endpoints
  (`/public/...`, `/auth/login`, forgot/reset/resend, invitation preview/accept) are non-enumerating: the same response
  whether or not the organization or account exists. Neither exists yet.
- Do not use `HandlerTypePredicate` selectors on one predicate expecting AND: they are OR. Combine with `.and(...)`.

## 7. Security rules (§15 — non-negotiable)

- Password storage uses Argon2id or bcrypt, plus a breached-password check; lockout/backoff; generic auth errors with
  consistent timing.
- Access JWT 15 min (in-memory client-side), `kid` key rotation; refresh token rotating in httpOnly Secure SameSite
  cookie, **reuse detection revokes the family**, sliding 30 d + absolute 90 d cap `[confirm]`; CSRF (double-submit +
  Origin) on refresh/logout; locked-down CORS.
- MFA (TOTP) mandatory for Admin/Super Admin (D6), including a directly invited Admin before first workspace access,
  and optional for Employees; recovery codes; **step-up auth** for the actions listed in §8.3.
- *From B2 (v9):* login is **Organization + company email + password** for every role with **no role selector** and one
  generic failure message (D27); a deactivated user immediately loses login, refresh, API, SSE and device authority
  while history is preserved (D26); the tenant-isolation gates in spec §15.1 apply. See "v9 adoption" in §2.
- Approval email tokens: single-use, **hashed at rest**, bound to (request, approver), expiring; **GET is read-only**,
  decision via POST with CSRF (D8, §7.3).
- CSV/Excel: reject formula-leading cells (`= + - @`, tab, CR) on import, escape on every export; size/row limits.
- **Audit log is append-only** (the runtime DB role cannot UPDATE/DELETE/TRUNCATE, and a trigger rejects them for
  everyone; it may SELECT for the future viewer, B0-6/2, B0-6/4); log logins, privileged actions, role changes, exports,
  attendance edits, corrections, month lock/unlock, device pairing, settings changes. Write only through
  `AuditWriter.append` inside the business transaction, with a real organization id, and never put secrets or free-text
  personal data in `details` (B0-6/8, B0-6/11).
- Rate limit login, reset, approvals, check-in/out, exports, agent endpoints (Bucket4j + Redis).
- **No PII in logs or Sentry** — log employee ids, never names/emails/phones. **No secrets in the repo**; config comes from
  environment variables. Least-privilege DB roles.
- Delete Admin = **tombstone/anonymise**, never a hard delete (D7). Employees are never hard-deleted (§3.2).
- If a change weakens any item here, stop and ask.

## 8. Testing rules (§14.4)

- Every phase/branch ships **unit + integration tests**. Integration tests use **Testcontainers** (real Postgres/Redis) —
  no H2 substitutes for Postgres-specific behavior (partial unique index, `btree_gist` exclusion constraints,
  `timestamptz`).
- **Every endpoint has a negative authorization test** (wrong role, other employee's data, unauthenticated; from B2
  also **another organization's data**).
- *From B2 (v9, spec §15.1, §22.1):* tenant tests are part of the definition of done for tenant-owned code: malicious
  foreign-tenant UUID substitution on read/update/delete/export/approval/calendar/report endpoints; organization and
  account enumeration resistance (wording **and** timing) for login, forgot-password and resend; and tenant-leakage
  tests for SSE, Redis/cache keys, scheduled jobs, exports and email payload builders. The DB-level tenant defense (RLS
  or equivalent) is exercised in integration tests, not merely documented.
- Time-based logic uses an injectable clock: day split, DST-safe durations, org timezone, catch-up after downtime.
- Concurrency and idempotency cases get real tests (e.g. two parallel check-ins → one session; double approval →
  one balance change).
- Migration tests: Flyway applies cleanly from empty DB; constraints actually reject bad data (e.g. audit
  `UPDATE/DELETE` fails).
- Validation tests assert the RFC 7807 `fieldErrors` shape. Pagination tests assert envelope, sort, and max-size.
- **Two test styles.** `@ApiWebTest` = `@WebMvcTest` slice against `SampleApiController` (test-only, `api-test`
  profile): fast, no Docker, use it for controller/advice/filter/pagination behaviour. `@IntegrationTest` = full
  context with Testcontainers: use it for anything touching the database, Flyway, Redis or springdoc. Test-only
  controllers must be `@Profile`-guarded so they never appear in normal runs or the published OpenAPI.
- Tests that claim "does not leak X" must assert on the **whole response body**, not one field.
- Run the tests and report actual results. A red or skipped test is reported, not hidden. Don't weaken or delete a test to
  get green without telling the user why.

## 9. Database & migration rules

- Flyway, forward-only, files under `src/main/resources/db/migration/` named `V<n>__<snake_case>.sql`. Never edit an
  applied migration; fix forward with a new one. Destructive changes: expand → migrate → contract.
- Schema follows §12; enforce invariants **in the database** (unique/partial-unique/exclusion/check constraints), not only
  in code. Key ones: one open session per employee, no overlapping sessions, `check_out_at > check_in_at`, unique
  `(device_id, seq)`, unique `(ref_type, ref_id, entry_type)` on the leave ledger.
- **The one exception to "`timestamptz` everywhere": the `shedlock` table (V2)** uses plain `timestamp` holding UTC.
  It is ShedLock's own table (nothing else reads or writes it) and this is deliberate: with the database clock,
  ShedLock writes a `timestamp without time zone`, and a `timestamptz` column re-reads it in each connection's
  session time zone, so instances in different zones disagree on lock expiry. Measured on real PostgreSQL: a live
  lock stolen (double execution) or stuck for hours after a crash in 5 of 6 zone pairs; plain `timestamp` was correct
  in all 6. `ShedLockTimeZoneTest` fails if the columns are ever changed. Do not "fix" it, and do not copy the
  pattern to any business table. The runtime DB role has `SELECT, INSERT, UPDATE` on `shedlock` and no `DELETE`
  (V3; measured, B0-6/13).
- JPA Buddy output is a **draft only**; a human-reviewable migration is required before any commit (§21).
- Hibernate `ddl-auto` is `validate` or `none` — never `update`/`create` outside throwaway tests. Flyway runs with
  `clean-disabled`, `validate-on-migrate`, no out-of-order, no baseline (set in `application.yml`; do not loosen).
- **Two DB roles (built in `b0-6`, B0-6/3):** a migration/owner role (Flyway, via `spring.flyway.user/password`) and a
  least-privilege runtime role for the app (`spring.datasource.*`), which has no `UPDATE`/`DELETE`/`TRUNCATE` on
  `audit_log` (§12). Flyway never creates roles; infrastructure provisions them first. Do not make Flyway and the app
  share one set of credentials. **Every migration grants the runtime role table by table, with exactly the privileges
  the app needs and no `ALTER DEFAULT PRIVILEGES`**, and updates `RuntimePrivilegesTest` (a fail-closed inventory of
  every table's runtime privileges) in the same change. The runtime role name reaches SQL only through the
  `runtime_role` placeholder, which `RuntimeRolePlaceholderGuard` validates (B0-6/14). (v9's PostgreSQL RLS defense,
  D30, also needs a non-owner runtime role, so this design serves both.)
- **Tenant schema (B2, spec §12.1; not before):** tenant-owned tables carry `organization_id NOT NULL` (or an
  unambiguous tenant path), with tenant-safe composite uniqueness/foreign keys where practical, and the DB-level tenant
  policy is proven by integration tests. Spec §12's base tables still write `org_id`; treat it as the same tenant key
  and confirm the exact column name before `b2-1` (§15 item 12).
- Tests use the real Postgres image via Testcontainers (`@IntegrationTest`, `support/TestcontainersConfiguration`); keep
  the image tag in one place and keep it equal to what production will run once hosting is decided.

## 10. Definition of Done & PR checklist (§16.3)

A phase/branch is done only when: merged via PR with green CI; tests (incl. authorization and time edge cases) pass;
OpenAPI and docs updated; migrations reviewed; audit and notifications wired where the spec requires; the phase's
security checklist items are ticked; demo-able through API tests.

PR checklist (§16.2): tests added · negative-auth test (from B2 including a cross-tenant one) · audit-log entry ·
migration reviewed · OpenAPI updated · docs updated · security notes · (screenshots N/A for backend). Required CI
checks: build, unit + integration tests, lint,
SAST, dependency scan, secret scan, OpenAPI breaking-change check.

## 11. Logging & observability (§14)

- Structured JSON logs (Logback), one line per event, MDC carries **correlation id + actor id** (`SYSTEM`/job name for
  jobs); the correlation id is the same one returned in RFC 7807 errors.
- Levels are environment-driven: DEBUG local, INFO staging/prod, WARN for retries/degradation, ERROR = needs a human. No
  verbose SQL/DEBUG shipped to prod. Request-logging filter (method, path, status, duration, correlation id), excluding
  health checks.
- Actuator health (liveness) + readiness that checks DB/Redis; graceful shutdown enabled; Sentry with PII scrubbing.
- **As built in `b0-4`** (`common/logging`, `common/observability`; decisions D1–D9 in §2): the request log records the
  matched route template, never the raw path or query; `ActorId` is the only way to set the actor in MDC (B2 auth and
  jobs call it); exception messages are never rendered (`MessageFreeStackTracePrinter`, Sentry allowlist scrubber,
  Hibernate's `org.hibernate.orm.jdbc.error` logger off), so **never put a value in a log message or an exception
  message expecting it to be scrubbed**; only `health` is exposed on Actuator until B2; liveness ignores DB/Redis,
  readiness checks them. Tests that claim "no PII in logs/Sentry" assert on the whole captured output/envelope.
  *Multi-tenant (v9, from B2):* an internal organization id may be added to log context for support correlation but is
  never exposed across tenants (spec §15.1); logs and Sentry must not leak one tenant's PII to another's surface, and the
  Sentry tag allowlist is extended deliberately when the organization id is added.

## 12. Containers & environments (§16.4)

- Dockerfile: multi-stage, JRE-only runtime, **non-root**, Actuator health check. Resource limits on every container.
- Config is `.env`-driven: only `.env.example` (names, no real values) is committed; real `.env` stays git-ignored.
- **Stop grace period must be 90s or more** (`terminationGracePeriodSeconds` / Compose `stop_grace_period` / the host's
  equivalent), above the 65s worst-case shutdown (slow request then slow job, each up to the 30s lifecycle timeout, plus 5s unwind).
  Never leave the orchestrator default (Compose 10s, Kubernetes 30s). **Check this when writing the Dockerfile, in
  `b0-7`, and whenever production hosting is chosen.** Details: §2, "B0-5 decisions". Spec v9 §16.5 requires the same
  of `b0-7` ("orchestrator shutdown grace/budget consistent with the b0-4/b0-5 worst case").
- **Docker Compose location is an open conflict** between §16.4 (`peoplehub-infra`) and §17 B0 (`b0-7-docker-compose`,
  this repo). Do not start `b0-7` until the user has resolved it.

## 13. Build order (backend track, §17)

B0 Foundation → B1 Email/notifications → B2 **Org, tenant isolation, employees & auth** → B3
Roles/departments/calendar/lifecycle → B4 Attendance core → B5 Devices/shutdown events → B6 Attendance reports → B7
Approval engine → B8 Corrections → B9 Leave → B10 Comp-off → B11 Admin reports & month lock → B12 Presence → B13
Hardening/retention/DR → B14 QA & freeze (`v1.0.0`).

**B2 (v9)** is where the multi-organization requirements land: multi-org tenant schema + DB isolation, organization
registration/bootstrap, founder verification, founding Super Admin, Organization+email+password login, JWT + rotating
refresh, Employee/Admin invitations, activation, direct Admin invite and promotion, deactivation/revocation, password
policy/lockout, sessions, TOTP + step-up. Branches `b2-1-org-tenant-employee-schema`,
`b2-2-org-bootstrap-founder-verification`, `b2-3-login-jwt-refresh-tenant-context`,
`b2-4-invite-activation-admin-invite`, `b2-5-password-policy-lockout-reset`, `b2-6-sessions-deactivation-revoke`,
`b2-7-mfa-stepup-onboarding`, `b2-8-tenant-isolation-security-tests` (spec §17).

- **Each phase merges before the next starts.** Order matters (e.g. approval engine B7 before corrections B8; email B1
  before invites in B2). Do not pull work forward.
- **B0 branches:** `b0-1-skeleton-ci` · `b0-2-db-flyway-testcontainers` · `b0-3-api-standards-pagination-openapi` ·
  `b0-4-logging-validation-observability` · `b0-5-scheduler-shedlock` · `b0-6-audit-log-append-only` ·
  `b0-7-docker-compose` (each prefixed with a `<type>/`).
- **B0 exit criteria:** CI green; sample migration; OpenAPI published; audit table rejects UPDATE/DELETE;
  `docker compose up` boots the stack; a deliberately bad request returns a field-level RFC 7807 error.
- **B0 progress:** `b0-1` … `b0-5` merged; `b0-6` implemented on its branch, awaiting review (not merged); `b0-7` not
  started. v9 (§16.5) says to continue from this actual state and not to rebuild merged work; `b0-7` remains deferred
  (§12, §15 item 1). The B0 exit criterion "audit table rejects UPDATE/DELETE" is demonstrated by
  `AuditLogMigrationTest.auditTableRejectsUpdateAndDelete`.

## 14. Local environment notes

- Windows 11; PowerShell and Git Bash both available. **JDK 21 (Temurin)** installed. **Docker Desktop + Compose**
  installed; it must be **running** for `./mvnw test`/`verify` (Testcontainers) and `./mvnw spring-boot:test-run`. The
  machine's Docker also runs the owner's **unrelated containers (`assessment-platform-*`): never stop, remove or
  modify them**, and never stop Docker Desktop to test a failure mode.
- **No global Maven/Gradle** and **no `gh` CLI** — use `./mvnw`. Anything needing GitHub (PRs, branch protection, CI
  status) is done by the user or after they install/authorize `gh`.
- **Avast HTTPS scanning re-signs TLS** on this machine (Windows trusts its root, Java does not), so Maven fails with
  `PKIX path building failed`. Workaround, per shell, never committed: `export
  MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT"`. Never disable certificate checks or the antivirus.
- **Build gate:** `./mvnw -B -ntp verify` = compile + tests + Spotless (google-java-format, **AOSP**, bound to `verify`).
  Fix formatting with `./mvnw spotless:apply`. Do not hand-wave a red lint: format, don't suppress.
- Git has `core.autocrlf=true`; `.gitattributes` (added in `b0-1`) forces LF, with `*.cmd` as CRLF. `mvnw` must stay LF
  and be committed with mode `100755` (`git ls-files -s mvnw`), or CI fails.
- Do **not** verify line endings with `grep -c $'\r'` in this shell (it matches every line); use
  `tr -cd '\r' < file | wc -c` or `git ls-files --eol`.
- Secret scanning: CI runs pinned gitleaks over full history. Known false positives go in `.gitleaksignore` as exact
  fingerprints with a comment (never a path-wide allowlist, never a real secret).
- IntelliJ plugins in use (§21): Lombok, JPA Buddy, SonarQube for IDE, GitToolBox, OpenAPI (Swagger) Editor, EnvFile.
  `.idea/` is git-ignored; nothing IDE-specific is required to build.

## 15. Known spec issues (recorded, NOT yet fixed — spec is not edited until the owner decides)

Resolve each **before** the phase named; batch fixes into one `docs:` spec PR. Until then, follow this table.

**Reconciled against v9 (spec adopted; rows 1–9 are unchanged and were each re-checked against the v9 text).** v9
resolves **none** of them, so all nine remain open or as recorded owner decisions:

- **1, 4, 5** (compose location, B0 scope items with no branch, compose-for-Testcontainers): v9 §16.4 and the §17 B0 row
  are unchanged. v9 §16.5 now lists `b0-7` as "not started, implement docker-compose", but says nothing about *where*
  the compose file lives, so it does not resolve #1; `b0-7` stays deferred.
- **2** (`approver_id` vs `resolved_approver_id`): unchanged (§12 vs §7.5). Still ask before B7.
- **3** (§2 cross-refs to 13.1/13.2): unchanged in v9's §2 table. Still cosmetic; use §13.2 / §13.3.
- **6, 7, 8** (page-size cap, RFC 7807 wording and `your-domain`, `b0-4` naming): unchanged; the recorded `b0-3` owner
  decisions still stand.
- **9** (`/actuator/health` as liveness): unchanged in v9 §16.4 and §22. The `b0-4` owner-approved deviation stands (the
  spec text is still to be corrected).

Rows 10–13 are new inconsistencies **inside v9 itself** (or between v9 and the repository), found while reconciling.

| # | Issue | Resolve before | Working assumption until resolved |
|---|-------|----------------|-----------------------------------|
| 1 | `docker-compose.yml` location: §16.4 says `peoplehub-infra`; §17 B0 says this repo (`b0-7`) and includes a frontend container that doesn't exist until F0 | `b0-7` | **Deferred.** Do not start `b0-7`. |
| 2 | `approval_request` column is `approver_id` in §12 but `resolved_approver_id` in §7.5 | B7 | Ask before creating the table. |
| 3 | §2 cross-refs are stale: validation is §13.2 (cited as 13.1), caching is §13.3 (cited as 13.2) | next spec PR | Cosmetic; use §13.2 / §13.3. |
| 4 | §17 B0 scope items with no branch: Redis, injectable clock, Dockerfile (16.4 puts the Dockerfile in this repo) | assigned per branch plan | Clock → `b0-1` (done); Redis → `b0-2` (done); Dockerfile → separate branch, not gated on the compose decision. |
| 5 | §16.4 says the compose file is what CI uses for Testcontainers; Testcontainers manages its own containers and does not consume compose | `b0-2` | Testcontainers is self-contained; CI does not depend on compose. |
| 6 | §13.1 gives `size` a max of 100 but does not say what happens above it | decided in `b0-3` (owner approved) | `size` > 100 is a 400 (`invalid-page-request`) that points to the export; no silent clamp. |
| 7 | §13 says "RFC 7807"; RFC 9457 obsoletes it with the same shape. §9.2 still has the `your-domain` placeholder, so no real base URL exists for problem `type` | decided in `b0-3` (owner approved) | Cite RFC 9457; `type` and `instance` are URNs (`urn:peoplehub:problem:*`, `urn:peoplehub:request:*`). |
| 8 | §17 names `b0-4` "logging-validation-observability", but the RFC 7807 error body needs a correlation id and the validation `@ControllerAdvice`, both required by `b0-3`'s pagination errors | decided in `b0-3` (owner approved) | Correlation-id filter and `GlobalExceptionHandler` live in `b0-3`; `b0-4` adds JSON logging, actor id, request log, Sentry, actuator on top. |
| 9 | §16.4 names `/actuator/health` as the liveness endpoint, but Spring Boot's aggregate `/actuator/health` includes the database and Redis, so using it for restarts would let a DB/Redis blip restart the container | decided in `b0-4` (owner approved, D1) | Liveness is `/actuator/health/liveness`, readiness (adds DB + Redis) is `/actuator/health/readiness`; the Dockerfile health check uses liveness. Spec text to be corrected in the next spec PR. |
| 10 | v9's header checkpoint and §16.5 table are **stale for the repository**: they say `b0-5` is "implemented locally, **not yet committed**" and the `b0-4` docs PR is "awaiting manual merge". Both are done (b0-4 docs PR #5, b0-5 PR #6). They also describe the 5s as a "second shutdown wait", whereas the measured behaviour is an unwind allowance for an interrupted job (`await-termination: false`; see "B0-5 decisions") | next spec PR | This file's status line and "B0-5 decisions" describe the real state; do not "re-do" b0-5 because of v9's checkpoint wording. |
| 11 | v9 §13.3 (last bullet) still says a multi-tenant future is "currently out of scope, Section 1", which contradicts D21, §1 and §2.1 (multi-organization is in scope) | next spec PR | Multi-organization is in scope (D21–D30); cache keys are organization-scoped. |
| 12 | Tenant key naming: §12's base tables (`department`, `employee`, `calendar_event`, `month_lock`, `leave_type`) write `org_id`, while §2.1.1/§12.1 use `organization_id` and §13.3 writes `orgId` | before `b2-1` | Treat all as the same tenant key; **confirm the exact column name with the owner before creating the tenant schema.** `audit_log` (B0-6/1) already uses `organization_id`; if the owner picks `org_id` for the other tables, that table needs a forward migration to match. |
| 13 | v9 §16.5 lets `b0-6` add `organization_id` "forward-compatibly", and §12.1 says audit rows carry it, but no `organization` table exists until B2 | before `b0-6` | **B0-6 design resolved; B2 completion pending.** B0-6/1: `audit_log.organization_id UUID NOT NULL`, no FK, no fabricated value ever. B2 still owes: the `organization` table, a matching primary-key type (UUID), the `audit_log` FK, tenant RLS or an equivalent DB defence in depth, and cross-tenant integration tests. Do not implement B2 early. |

## 16. Never do

- Commit to or push `main`; commit/push/PR/merge/tag/deploy without explicit approval.
- Edit an applied migration, hard-delete an employee, or make an audit row mutable.
- Trust a client-supplied employee id, department id, or timestamp for authorization or attendance.
- Add `beforeunload`-style or client-driven auto check-out; end a session for any reason not in R3.
- Log PII, commit secrets, or add an unvalidated write endpoint or an unpaginated list endpoint.
- Cache open sessions, balances, `attendance_day`, or the live dashboard.
- Implement work from a later phase, or change scope without a spec update. In particular, do not build B2/F1
  organization-bootstrap, tenancy, login or invitation functionality inside B0 (v9 §16.5).
- From B2: accept an `organization_id` (or any tenant selector) from a client, answer "forbidden" instead of "not found"
  for another tenant's object, or reveal that an organization or account exists.
