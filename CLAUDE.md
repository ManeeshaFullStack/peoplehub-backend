# CLAUDE.md — PeopleHub Backend (`peoplehub-backend`)

Persistent engineering rules for Claude in this repository. This file is a **condensed operating guide**;
`PROJECT_MASTER_SPEC.md` (v8) is the **single source of truth**. Section numbers below (e.g. `§4.5`, `D9`) refer to it.

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

- Single-organization HR ops backend: attendance (server-authoritative sessions), leave, approvals, org/departments,
  calendar, notifications, reports, audit. Standalone (own auth/org/data).
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
- Current status: **B0-1, B0-2 and B0-3 are merged; their code is in the baseline commit** (the individual merge
  commits no longer exist). **B0-4 (logging, request log, Sentry, health probes, graceful shutdown) is merged
  (PR #4).** B0-5 (`feature/b0-5-scheduler-shedlock`) is next. Update this line when a phase merges.
- **Queued follow-ups (not yet scheduled):** (1) CI guard that fails when an already-merged migration file under
  `db/migration/` is modified or deleted (§16.2 "never edit an applied migration"); (2) gitleaks pre-commit hook
  (§15.12); (3) SAST, dependency scan, SBOM, **and the OpenAPI snapshot + breaking-change check** (§16.2) before B0
  closes (the OpenAPI check was deferred out of `b0-3` because there is no real API surface to compare yet); (4) the
  Dockerfile as its own branch.
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
  leave/attendance (D9).**
- **Time:** inject a `Clock`; never call `Instant.now()`/`LocalDate.now()` directly. Store UTC `timestamptz`; compute
  durations from instants in **seconds**; day boundaries use the **org timezone** (§4.2). All time logic must be
  testable with a fixed/advancing clock.
- **Server is the only clock (R5).** Attendance timestamps are server-set. The only client-influenced times are the
  bounded offline check-out (§4.6) and signed agent events (§4.4), both flagged.
- **Attendance golden rules R1–R6 are non-negotiable** (§4.1): only manual check-out, verified shutdown, estimated
  shutdown, deactivation, admin edit/approved correction, or the safety cap end a session; midnight *splits*, never
  ends. Rest counts (D1). Presence never affects hours or auth (§8.4).
- **Caching (D15/§13.3):** cache-aside via Spring Cache/Redis **only** for org settings, leave types, department list,
  calendar ranges; 5–15 min TTL + explicit `@CacheEvict` on every write; keys namespaced `org:{orgId}:…`. **Never
  cache** open sessions, `attendance_day`, leave balances/ledger, the live dashboard, or anything mid-approval.
- **Scheduled jobs:** `@Scheduled` + ShedLock, idempotent, catch-up after downtime, org-timezone day logic, failures
  alert (never silent). **As built in `b0-5`** (`common/scheduling`, decisions S1–S7 in §2): a job is a non-final bean
  with `@Scheduled` + `@SchedulerLock` whose body calls `jobRunner.run("<name>", …)` (name = lock name = actor
  `job:<name>`); the job derives its work from persisted state and decides "due" in the org timezone, so a run after
  downtime repairs the gap and a repeat run is a no-op; every job ships a proof test of that. No generic watermark
  table. Failures are logged once at ERROR by `JobRunner` (that is the Sentry alert), never rethrown, never silent.
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
- Do not use `HandlerTypePredicate` selectors on one predicate expecting AND: they are OR. Combine with `.and(...)`.

## 7. Security rules (§15 — non-negotiable)

- Password storage uses Argon2id or bcrypt, plus a breached-password check; lockout/backoff; generic auth errors with
  consistent timing.
- Access JWT 15 min (in-memory client-side), `kid` key rotation; refresh token rotating in httpOnly Secure SameSite
  cookie, **reuse detection revokes the family**, sliding 30 d + absolute 90 d cap `[confirm]`; CSRF (double-submit +
  Origin) on refresh/logout; locked-down CORS.
- MFA (TOTP) mandatory for Admin/Super Admin (D6); recovery codes; **step-up auth** for the actions listed in §8.3.
- Approval email tokens: single-use, **hashed at rest**, bound to (request, approver), expiring; **GET is read-only**,
  decision via POST with CSRF (D8, §7.3).
- CSV/Excel: reject formula-leading cells (`= + - @`, tab, CR) on import, escape on every export; size/row limits.
- **Audit log is append-only** (DB role cannot UPDATE/DELETE); log logins, privileged actions, role changes, exports,
  attendance edits, corrections, month lock/unlock, device pairing, settings changes.
- Rate limit login, reset, approvals, check-in/out, exports, agent endpoints (Bucket4j + Redis).
- **No PII in logs or Sentry** — log employee ids, never names/emails/phones. **No secrets in the repo**; config comes from
  environment variables. Least-privilege DB roles.
- Delete Admin = **tombstone/anonymise**, never a hard delete (D7). Employees are never hard-deleted (§3.2).
- If a change weakens any item here, stop and ask.

## 8. Testing rules (§14.4)

- Every phase/branch ships **unit + integration tests**. Integration tests use **Testcontainers** (real Postgres/Redis) —
  no H2 substitutes for Postgres-specific behavior (partial unique index, `btree_gist` exclusion constraints,
  `timestamptz`).
- **Every endpoint has a negative authorization test** (wrong role, other employee's data, unauthenticated).
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
  pattern to any business table. The `b0-6` runtime DB role needs `INSERT` and `UPDATE` on `shedlock`.
- JPA Buddy output is a **draft only**; a human-reviewable migration is required before any commit (§21).
- Hibernate `ddl-auto` is `validate` or `none` — never `update`/`create` outside throwaway tests. Flyway runs with
  `clean-disabled`, `validate-on-migrate`, no out-of-order, no baseline (set in `application.yml`; do not loosen).
- **Two DB roles are needed by `b0-6`:** a migration/owner role (used by Flyway via `spring.flyway.user/password`) and a
  least-privilege runtime role for the app, which must have no `UPDATE`/`DELETE` on `audit_log` (§12, §15.13). Don't
  design anything that forces Flyway and the app to share one set of credentials.
- Tests use the real Postgres image via Testcontainers (`@IntegrationTest`, `support/TestcontainersConfiguration`); keep
  the image tag in one place and keep it equal to what production will run once hosting is decided.

## 10. Definition of Done & PR checklist (§16.3)

A phase/branch is done only when: merged via PR with green CI; tests (incl. authorization and time edge cases) pass;
OpenAPI and docs updated; migrations reviewed; audit and notifications wired where the spec requires; the phase's
security checklist items are ticked; demo-able through API tests.

PR checklist (§16.2): tests added · negative-auth test · audit-log entry · migration reviewed · OpenAPI updated · docs
updated · security notes · (screenshots N/A for backend). Required CI checks: build, unit + integration tests, lint,
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

## 12. Containers & environments (§16.4)

- Dockerfile: multi-stage, JRE-only runtime, **non-root**, Actuator health check. Resource limits on every container.
- Config is `.env`-driven: only `.env.example` (names, no real values) is committed; real `.env` stays git-ignored.
- **Stop grace period must be 90s or more** (`terminationGracePeriodSeconds` / Compose `stop_grace_period` / the host's
  equivalent), above the 65s worst-case shutdown (slow request then slow job, each up to the 30s lifecycle timeout, plus 5s unwind).
  Never leave the orchestrator default (Compose 10s, Kubernetes 30s). **Check this when writing the Dockerfile, in
  `b0-7`, and whenever production hosting is chosen.** Details: §2, "B0-5 decisions".
- **Docker Compose location is an open conflict** between §16.4 (`peoplehub-infra`) and §17 B0 (`b0-7-docker-compose`,
  this repo). Do not start `b0-7` until the user has resolved it.

## 13. Build order (backend track, §17)

B0 Foundation → B1 Email/notifications → B2 Org/employees/auth → B3 Roles/departments/calendar/lifecycle → B4 Attendance
core → B5 Devices/shutdown events → B6 Attendance reports → B7 Approval engine → B8 Corrections → B9 Leave → B10
Comp-off → B11 Admin reports & month lock → B12 Presence → B13 Hardening/retention/DR → B14 QA & freeze (`v1.0.0`).

- **Each phase merges before the next starts.** Order matters (e.g. approval engine B7 before corrections B8; email B1
  before invites in B2). Do not pull work forward.
- **B0 branches:** `b0-1-skeleton-ci` · `b0-2-db-flyway-testcontainers` · `b0-3-api-standards-pagination-openapi` ·
  `b0-4-logging-validation-observability` · `b0-5-scheduler-shedlock` · `b0-6-audit-log-append-only` ·
  `b0-7-docker-compose` (each prefixed with a `<type>/`).
- **B0 exit criteria:** CI green; sample migration; OpenAPI published; audit table rejects UPDATE/DELETE;
  `docker compose up` boots the stack; a deliberately bad request returns a field-level RFC 7807 error.

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

## 16. Never do

- Commit to or push `main`; commit/push/PR/merge/tag/deploy without explicit approval.
- Edit an applied migration, hard-delete an employee, or make an audit row mutable.
- Trust a client-supplied employee id, department id, or timestamp for authorization or attendance.
- Add `beforeunload`-style or client-driven auto check-out; end a session for any reason not in R3.
- Log PII, commit secrets, or add an unvalidated write endpoint or an unpaginated list endpoint.
- Cache open sessions, balances, `attendance_day`, or the live dashboard.
- Implement work from a later phase, or change scope without a spec update.
