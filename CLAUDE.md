# CLAUDE.md — PeopleHub Backend (`peoplehub-backend`)

Persistent engineering rules for Claude in this repository. This file is a **condensed operating guide**;
`PROJECT_MASTER_SPEC.md` (**v9**, which supersedes v8) is the **single source of truth**. Section numbers below (e.g.
`§4.5`, `D9`) refer to it. v9 keeps the v8 numbering (§0–§22) and adds §2.1, §10.4, §12.1, §13.0, §15.1, §16.5 and
§22.1.

> **Two different `D#` series exist.** The spec has decisions D1–D30 (its §0). This repository also records its own
> implementation decisions as **"B0-4 decisions" D1–D9**, **"B0-5 decisions" S1–S7**, **"B0-6 decisions"
> B0-6/1–B0-6/16** and **"B0-7 decisions" B0-7/1–B0-7/13** (all in §2 below). A `D#` in the B0-4 block, or next to logging/health/Sentry/shutdown topics (for
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
  Standalone (own auth/org/data). **Built so far in B2 (b2-1 to b2-7):** the organization/tenant/employee schema,
  organization registration with founder email verification, password login with JWT, rotating refresh tokens and the
  tenant context, invitation-only membership (Employee and direct Admin invitations, activation, the welcome state),
  the password policy with a breached-password check, per-account lockout, forgot/reset and change password, and the
  sessions list with session revocation and coarse device labels, and employee deactivation/reactivation; and (b2-7)
  the organization MFA policy, TOTP enrollment and sign-in challenge, recovery codes, step-up authentication, MFA
  reset, Employee → Admin promotion and founder onboarding completion.
  **Not built yet:** database-level tenant isolation (RLS) and the cross-tenant security suite arrive in b2-8.
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
  **b0-5 is merged** (ShedLock scheduler, `JobRunner`, V2 lock table; PR #6). **b0-6 is merged** (V3 `audit_log`,
  append-only trigger, two DB roles, `AuditWriter`; PR #8). **b0-7 is merged**
  (`feature/b0-7-docker-compose`: `Dockerfile`, backend development `docker-compose.yml`, `docker/smoke.sh`, CI job
  `compose-smoke`; PR #9). **B0 Foundation complete: b0-1 through b0-7 merged and verified.** The master spec is **v9**;
  its own §16.5 checkpoint is stale for b0-4/b0-5/b0-6 (see §15 item 10). Update this line when a phase merges.
- **B1 status:** b1-1, b1-2, b1-3 and b1-4 are merged (Email & notification platform: outbox
  foundation, templates/retry/sending, in-app notifications + SSE, bounce/complaint suppression;
  PRs #11, #12, #13, #15). **B1 (Email & notification platform) complete.** See §13 for the
  branch-by-branch detail.
- **B2 status:** b2-1, b2-2, b2-3, b2-4, b2-5, b2-6 and b2-7 are merged (organization/tenant/employee schema V8-V12,
  PR #17; organization registration and founder verification V13, PR #20; password login, JWT and refresh-token
  rotation V14, PR #23; invitations, activation and the welcome state V15, PR #26; password policy, lockout and reset
  V16-V17, PR #29; sessions, session revocation and deactivation V18, PR #32; the MFA spec correction, PR #35; MFA,
  step-up, promotion and onboarding V19-V23, PR #36). **Next: `b2-8-tenant-isolation-security-tests`.** B2 is not
  complete (b2-8 remains). See §13 for the branch-by-branch detail.
- **Queued follow-ups (not yet scheduled):** (1) CI guard that fails when an already-merged migration file under
  `db/migration/` is modified or deleted (§16.2 "never edit an applied migration"); (2) gitleaks pre-commit hook
  (§15 item 12); (3) SAST, dependency scan, SBOM, **and the OpenAPI snapshot + breaking-change check** (§16.2) before B0
  closes (the OpenAPI check was deferred out of `b0-3` because there is no real API surface to compare yet); (4) ~~the
  Dockerfile as its own branch~~ (done inside `b0-7`, B0-7/2); (5) **`CODEOWNERS`** (repository governance): not created because the owner's GitHub
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
  confirm the container runs in UTC or force UTC then. **Resolved in `b0-7` (B0-7/5):** the image sets `ENV TZ=UTC`, and
  `docker/smoke.sh` checks that the container's log timestamps are UTC (`Z`). Still no custom formatter; a JVM started
  outside the image (for example `./mvnw spring-boot:run`) keeps the machine's zone.
- **Open decision item (raised in `b0-4`): Sentry scrubber is a denylist by name for top-level fields.**
  `SentryEventScrubber` allowlists tags and contexts but clears the other data-carrying event fields by name, so a
  new top-level field added by a future SDK version would not be cleared automatically. Owner decision: leave as is
  for `b0-4` (do not rebuild it into a true allowlist now). **Re-check the class every time `sentry.version` is
  bumped**; if it is still wanted then, rebuild the event from permitted fields only, with a test that fails if any
  unlisted field survives.
- **Owner decisions (initialization):** Maven + Maven Wrapper (not Gradle). `PROJECT_MASTER_SPEC.md` stays in this
  repo for the current implementation phase (not moved to `peoplehub-docs`) and remains the source of truth.
  `.gitignore`, `CLAUDE.md`, `PROJECT_MASTER_SPEC.md`, `README.md` and `doc/` (when it exists) are tracked project
  files and must **never** be added to `.gitignore`. `b0-7` was deferred until the compose location was decided; the
  owner chose Option C (B0-7/1) and it is now built.
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
  **Placement (owner decision, B2-3/20): RLS is built in `b2-8-tenant-isolation-security-tests`.** `b2-3` only
  creates the tenant context that RLS will later read.
- **Registration / bootstrap (D23, D29, §2.1.3):** only an organization **founder** self-registers (public
  `/register`). Organization + founder are created atomically; the founder is an individual (own company email and
  private password, no shared "superadmin" credentials) and becomes the first `SUPER_ADMIN`. Flow: email verification ->
  first-time organization setup -> the real Super Admin dashboard. **MFA is not a bootstrap step** (owner decision
  MFA/1, "MFA policy decisions" below; the spec was corrected to match by PR #35, §15 item 15). No normal session exists
  before verification; responses are non-enumerating.
- **Invitation-only membership (D24, D25, §2.1.5, §3.3):** Employees and additional Admins never sign up publicly. They
  arrive by single-use, expiring email invitations and set their own passwords privately. A Super Admin may promote an
  existing active Employee (step-up protected) **or** invite a new person directly as `ADMIN`. An Admin or Super Admin
  is always an Employee record (`role` is a relation, not a separate identity).
- **Login (D27, §2.1.6, §8.2):** every role uses **Organization + company email + password**; there is no role
  selector. The server resolves the tenant, authenticates inside it, then routes by the stored role. Failure is one
  generic message with timing and rate-limit protection.
- **MFA (owner-approved decision, 2026-09-23; see "MFA policy decisions" and "B2-7 decisions" below; the spec's
  former mandatory-MFA text was corrected by PR #35, §15 item 15):** a configurable, organization-level security
  feature, **disabled by default** after organization creation. Whether MFA is required, and for whom, is the
  organization's MFA policy, chosen by a Super Admin; anyone enrolled is always challenged. **Built in `b2-7` (PR #36).**
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

### Configurable leave, comp-off & weekend policy (owner-finalized requirements, 2026-09-23; FUTURE-PHASE — none of this is built yet, and none of it is retrofitted into B2-1's already-merged schema)

The owner finalized a set of multi-tenant HR-policy requirements ahead of the phases that implement them. Cross-checked against `PROJECT_MASTER_SPEC.md` §3.2, §4.9, §6.1–§6.6, §11, §12 and §17: **most of this reaffirms design the spec already adopted**; a few items are genuinely new; **one item conflicts with the spec's current text** and is recorded, not resolved, below and in §15's table. This note does not edit `PROJECT_MASTER_SPEC.md` — a conflict or a genuinely new requirement becomes a spec change only through the normal branch + PR process (§16.2), at the owner's decision.

- **Leave policy engine.** Reaffirms spec §6.1 (`leave_type` is already a data-driven table: accrual amount/period, carry-forward, expiry, `allow_negative`, `requires_document`, all per type, all per organization) and §11 (org settings are already data-driven, never hardcoded). **New, not yet in the spec:** an explicit **maximum balance limit** field per leave type, and an Admin-facing flow for an organization to define its own **custom leave type codes** beyond casual/sick/comp-off/earned/LOP. Different organizations already can and do have different per-type accrual amounts under the existing design (Spec §6.1); this is not a new capability, only a restated requirement.
- **Leave accrual scheduler.** Already the plan: spec §17's `b9-2-accrual-prorata-job` branch is exactly "monthly leave accrual runs on organization policy." No new requirement; reaffirmed. Follows the `common/scheduling` pattern already built in `b0-5` (job derives work from persisted state, decides "due" in the org timezone, catch-up after downtime — S1–S7 in §2 above).
- **Comp-off approval workflow — RESOLVED by owner decision, 2026-09-23.** Rejects spec §4.9's current automatic-credit design. **Locked:** weekend/holiday work never directly credits comp-off balance. It creates a **comp-off earning request** (pending state); a manager/admin must **approve or reject** it; only an **approved** request adds balance. A rejected or still-pending request adds nothing. This supersedes §4.9's "credit is issued when the day is finalized by the nightly job" — that text is now wrong and needs correcting through a `docs:` spec PR (§16.2) before or alongside `b10-1`; this file records the decision, it does not itself edit the spec. Required fields on the request, all owed by the eventual schema (not built yet, §17 note below):
  - **approval status** (`PENDING` / `APPROVED` / `REJECTED`)
  - **approver identity** (the deciding Admin/Manager's employee id — never a name, matching this repo's `actor_id`/`ActorId` convention elsewhere)
  - **approval timestamp** (server time, database-generated — same discipline as every other "the app never supplies the time" rule in this file)
  - **rejection reason** (required when rejected, optional/absent otherwise)
  - **audit history** (every decision written through `AuditWriter.append` inside the same transaction as the decision, per §7 "Audit log is append-only... log logins, privileged actions..."; a comp-off decision is exactly this kind of privileged action)

  This is also the same shape of decision the spec's own approval engine already builds for leave/corrections (§7: "one engine, two request types," `approval_request`) — worth deciding, when `b10`/`b7` design actually happens, whether comp-off becomes a third request type on that same engine or its own smaller workflow; not decided here, flagged for that phase.
- **Comp-off expiry configuration.** Already spec'd: §11 has `comp_off_expiry_days` (default 90, "v2's 3-month expiry, `[confirm]`") as a per-organization setting. No new requirement; reaffirmed. The owner's examples (Company A 90 days, Company B 180 days) are exactly what the existing `[confirm]` org-setting design already supports — nothing hardcodes 90 days anywhere in the codebase today.
- **Leave application preview.** Already spec'd: §6.3 says "Balance and projected-after-request balance shown before submit." No new requirement; reaffirmed.
- **Weekend policy — organization level.** Already spec'd: §11 has `weekly_off_days` (default SAT, SUN, "editable") as an org setting. The 1-or-2-day cardinality framing is a restatement of the same capability, not a new one.
- **Weekend policy — department override.** **Genuinely new** — no such concept exists in spec §3.1's department model today. Needs its own table (e.g. `department_weekend_policy`) once `department` exists (B3, not before). Overrides the organization default per spec's existing settings-resolution spirit (most-specific-wins), extended one level.
- **Weekend policy — employee override (rotational shifts).** **Genuinely new** — no per-employee weekly-off override exists in spec today. Needs `employee`, `weekend days`, `effective_from`, optional `effective_to` — i.e. its own small versioned table, not a plain column (the same reasoning that gives `leave_type` policy an `effective_from`/`effective_to` pair, §6.1). Most specific level in the resolution order: employee > department > organization.
- **Leave balance history and auditability.** Already spec'd, close to verbatim: §6.2's `leave_ledger` is exactly this — `OPENING`, `ACCRUAL`, `PRORATA_ACCRUAL`, `DEDUCTION`, `RESTORE`, `CARRY_FORWARD`, `EXPIRY`, `ADJUSTMENT`, `COMP_OFF_CREDIT`, `COMP_OFF_USE` entry types, and §12's schema already carries `created_by` and `note`. `leave_balance` is explicitly "a cache of `sum(leave_ledger)`" (§6.2), never the source of truth. No new requirement; reaffirmed.
- **Leave policy versioning.** Already spec'd, near-verbatim: §6.1 says "Policy changes are versioned with an effective date; changing policy never rewrites past ledger entries." No new requirement; reaffirmed.
- **Admin permission control.** Already spec'd: §3.2's permission matrix already splits "Configure org settings" (Super Admin ✅, Admin ✅ except security settings) and requires every privileged action to be audit-logged (§15 security checklist item 9, `AuditWriter`). Extending this split to explicitly name the leave/comp-off/weekend-policy setting category (not just "security settings" as the one carved-out exception) is a small, natural extension of the existing matrix, not a new mechanism.
- **Holiday calendar.** Already spec'd: §6.6's `calendar_event` table (type `HOLIDAY`/`EVENT`) already exists, and §11's `comp_off_on_holidays` setting already governs whether holiday work is comp-off-eligible. No new requirement; reaffirmed. (Department/location-scoped holidays, mentioned as "if required later," are explicitly deferred by the owner's own note — not designed now.)
- **Half-day future compatibility.** Already spec'd, near-verbatim: §6.1 says "Half-day is deferred, but durations are decimals from day one" — the schema already will not need reshaping to add half-day support later. No new requirement; reaffirmed.
- **Comp-off rule configuration.** Already spec'd in part: §11's `comp_off_min_hours` (default 4, `[confirm]`) is already a per-organization setting. **New, not yet in the spec:** an explicit **eligible-days** field (which weekdays/off-days qualify, beyond the existing weekend/holiday split). The approval-requirement question is no longer open — approval is now always required (resolved above); there is no per-organization toggle for it.

**Phase placement — reconciled against the already-adopted build order (spec §17, CLAUDE.md §13), not the B2/B3/B4 grouping first proposed.** `b2-1` (merged) was deliberately kept to organization/tenant/employee schema only, per this file's own "one small reviewable change per branch" and "do not pull work forward" rules (§4, §13, §16) — pulling leave/comp-off/weekend-policy tables into B2 now would violate the exact discipline just followed. The spec's build order already assigns this work correctly:
- **B2** (`b2-1`…`b2-8`, in progress): no leave/comp-off/weekend-policy schema. Org/tenant/employee/auth foundation only, as already scoped.
- **B3** (Roles/departments/calendar/lifecycle): the natural home for anything that needs `department` to exist first — **department weekend override**, and the **generic org-settings API** (`b3-6-calendar-org-settings-cache` already covers org settings + calendar). Employee weekend override can land here too, once `department`/employee-lifecycle work is in place, or slide into B9 if it turns out to be leave-specific in practice — not yet decided.
- **B4** (Attendance core): weekend/holiday-aware day-type logic for attendance purposes (already implied by spec §4.3's `WORKED_ON_OFF_DAY`/holiday handling) — reaffirmed, not new placement.
- **B9** (Leave core, spec §17's own branches `b9-1-types-ledger` … `b9-5-approval-balance-hook`): the leave policy engine, accrual scheduler, leave application, ledger, and policy versioning — all already assigned here by the spec.
- **B10** (Comp-off, spec §17's own branches `b10-1-credit-on-finalize` … `b10-3-apply-comp-off`): comp-off management (now the resolved approval-gated workflow above — `b10-1`'s branch name "credit-on-finalize" will need revisiting to reflect approval-gating, not just day-finalization), expiry job, and comp-off rule configuration — already assigned here by the spec.

**Architectural warning (record verbatim, apply everywhere HR policy is touched):** Leave rules, accrual values, comp-off expiry, and weekend rules must never be hardcoded. All HR policy behavior must be organization configurable because PeopleHub supports multiple organizations with different policies.

**Do not implement yet.** Everything above is architecture/design requirements only. Do not build leave/comp-off/weekend-policy tables, controllers, UI, schedulers, or approval APIs until the relevant phase (B3/B4/B9/B10 per the placement above) actually begins. The comp-off approval-vs-auto-credit question is now resolved (approval-gated, above) — the remaining gate is only phase timing, not an open design decision.

### B2-2 decisions (owner-approved 2026-09-23; recorded here so they survive a session or repo reset)

Cite as "B2-2/4" and so on (never a bare `D#`, which is the spec's). Scope: `b2-2-org-bootstrap-founder-verification` — organization registration, founder creation, email verification, resend, verification token storage, email through the outbox. **Implemented and merged (PR #20).** See "B2 next-phase scope review" (this session) for the full scope/exclusion analysis this refines.

- **B2-2/1 — Password hashing: Argon2id.** A new multi-tenant HR SaaS should use a modern, memory-hard hashing algorithm (spec §8.2/§15 already accept either Argon2id or bcrypt; Argon2id is the pick). Store only the hash, never plaintext. Founder registration hashes the password before storing it (into `employee.password_hash`, already a column from `b2-1`'s `V9`). **Explicitly not in scope for `b2-2`: full Spring Security authentication** — no `SecurityFilterChain`, no JWT, no `AuthenticationManager`, no login endpoints. Those are `b2-3-login-jwt-refresh-tenant-context`'s job. Allowed here: a minimal password-hashing dependency only (e.g. `spring-security-crypto`'s `Argon2PasswordEncoder` used standalone, or an equivalent library) — not the full `spring-boot-starter-security` starter. This is a direct application of the B2 development rules already recorded in memory: never add the security starter and feature code in the same change; dependency first, verified, then code.
- **B2-2/2 — Breached-password validation: design only, no external dependency yet.** `b2-2` does not block registration on an external breached-password API (spec §8.2 leaves the choice open between a k-anonymity API and an offline list). Introduces a **`PasswordSecurityValidator`** abstraction (interface) now, so the check point exists and is swappable, without adding any external runtime dependency in this phase. Future implementations (HaveIBeenPwned k-anonymity API, or an offline compromised-password list) plug in later without changing the registration flow around it.
- **B2-2/3 — Rate limiting: recorded requirement, not built.** `b2-2` does not implement Bucket4j+Redis rate limiting. `POST /public/organizations/register`, `/verify-email` and `/resend-verification` are recorded as abuse-sensitive, enumeration-adjacent endpoints needing rate limiting later — spec §15 item 10 doesn't name registration explicitly (only "login, reset, approvals, check-in/out, exports, agent endpoints"), so this extends that list rather than contradicting it. Full implementation is deferred to a later security-hardening phase, the same "recorded now, built later" treatment already given to other owed items in this file (e.g. the "Owed from B4 onward" bullet in §2).
- **B2-2/4 — Founder lifecycle, locked.** Registration creates `organization.status = PENDING_VERIFICATION` (already `V8`'s default) and `employee.status = PENDING_VERIFICATION`, `role = SUPER_ADMIN`. **This resolves `V9`'s own flagged migration comment** (the §12-vs-§2.1.3 inconsistency over whether `employee.status` includes `PENDING_VERIFICATION`, previously recorded only as "a safe superset pending owner confirmation"): it is now confirmed as a real, used value, not a defensive superset. After successful email verification: `organization.status = ACTIVE`, `employee.status = ACTIVE`. No user session exists before verification (reaffirms D23/§2.1.3; still true, `b2-2` doesn't change it).
- **B2-2/5 — Verification token design (`V13`, created in b2-2).** New table `organization_verification_token`: `token_hash` only (**never** the raw token — the same discipline already applied to `employee_invitation.token_hash`, `refresh_token.token_hash` and `mfa_recovery_code.code_hash`, b2-1; this becomes the fourth table following that exact pattern), single-use (`consumed_at`), `expires_at`, and a required, real `organization_id` FK (organization already exists by the time this table is created — no "no FK yet" forward-compat needed, same reasoning already applied to every `b2-1` table). Flow: generate a random raw token → email the raw token → store only its hash → the user clicks the link → the submitted token is hashed → compared against the stored hash → consumed on match.
- **B2-2/6 — Email integration.** Uses the existing `EmailOutboxWriter` (b1-1) — never sends directly. Adds one new email `type`: **`ORGANIZATION_VERIFICATION`**, with its own classpath template (the `EmailTemplateRenderer` mechanism already built in `b1-2`).
- **B2-2/7 — Audit integration.** `b2-2` is `AuditWriter`'s (b0-6) **first real caller** — nothing has invoked it in production code since it was built, by design (B0-6/1: "no production code calls `AuditWriter` yet," acceptable until a real organization id exists). Records organization registration and founder verification through `AuditWriter.append`, inside the same transaction as the business change it describes (the existing `Propagation.MANDATORY` contract), now that `b2-1` gives it a real, non-fabricated organization id to write.
- **B2-2/8 — Scope boundaries.** **Included:** organization registration, founder employee creation, email verification, verification resend, verification token storage, email sending through the outbox. **Excluded:** login, JWT, refresh tokens, MFA, invitations, onboarding-wizard completion, tenant context, and the full authorization system — all later B2 branches (`b2-3` through `b2-8`), not pulled forward.

### MFA policy decisions (owner-approved 2026-09-23; recorded here so they survive a session or repo reset)

Cite as "MFA/3" and so on (never a bare `D#`, which is the spec's). **Owner-approved decision.** The spec's former
mandatory-MFA text (D6 "MFA is mandatory for Admin and Super Admin" and the MFA steps of §2.1.3 step 6, §2.1.5, §3.3,
§8.2, §8.3, §15 item 5 and §22.1) was corrected to this policy model by PR #35 before `b2-7` started (§15 item 15,
resolved). Implemented and merged in `b2-7` (PR #36); the details are "B2-7 decisions" below.

- **MFA/1 — MFA is a future, configurable security feature, not a login prerequisite.** No role is forced through MFA
  enrollment by default: not the founder, not a directly invited Admin, not a promoted Admin. MFA gates login only
  when the organization's MFA policy (MFA/4) requires it for that user.
- **MFA/2 — Phase placement.** **`b2-3` does not implement MFA.** `b2-3` implements only password authentication
  (Organization + company email + password), JWT access tokens, rotating refresh tokens, logout and tenant context.
  It builds no MFA gate, no restricted "MFA pending" session and no MFA claim-based access rules (this replaces the
  "restricted session" recommendation, L9, from the B2-3 scope review). **`b2-7` implements MFA.**
- **MFA/3 — Default: disabled.** A newly created organization starts with MFA policy `DISABLED`.
- **MFA/4 — Organization MFA policy options** (one per organization, set by a Super Admin, audited as a security
  settings change):
  - `DISABLED`
  - `OPTIONAL`: any user may enroll; nobody is required to.
  - `REQUIRED_FOR_ADMINS`: required for Admin and Super Admin.
  - `REQUIRED_FOR_SELECTED_USERS`: required for users a Super Admin explicitly selects.
  - `REQUIRED_FOR_ALL`: required for every employee.

  Value names are indicative; the exact names, the storage (org setting vs. its own table, and the per-user selection
  for `REQUIRED_FOR_SELECTED_USERS`) and the grace period for a newly required user are decided in `b2-7`.
- **MFA/5 — Enable flow.** A Super Admin enables MFA from the security settings (spec §3.2 already makes security
  settings Super Admin only). The UI shows a clear prompt encouraging activation. Users enroll through an
  authenticator app.
- **MFA/6 — Initial method: TOTP authenticator apps.** Existing storage: `employee.mfa_totp_secret` (V9) stores the
  encrypted TOTP secret; `mfa_recovery_code` (V11) stores the recovery codes separately (hashed). Other methods are
  out of scope.
- **MFA/7 — Reminders encourage, never block.** Security reminders encourage users (and Super Admins, for the
  organization policy) to enable MFA, but **never block login unless the organization's policy requires MFA for that
  user**.
- **Formerly open for `b2-7`, now decided:** step-up for a user with no MFA enrolled is the password alone, unless
  the policy requires MFA of them, when they enroll first (B2-7/15); changing the policy needs step-up (B2-7/3); a
  policy change that newly requires MFA ends the sessions of those not enrolled, with no grace period (B2-7/3); who may
  reset another user's MFA is B2-7/12.

### B2-3 decisions (owner-approved 2026-09-23; recorded here so they survive a session or repo reset)

Cite as "B2-3/4" and so on (never a bare `D#`, which is the spec's). Scope:
`feature/b2-3-login-jwt-refresh-tenant-context` — password login, JWT access tokens, rotating refresh tokens, logout,
tenant context, a minimal `/me`, and audit events for those actions. **Implemented and merged (PR #23).** Spec basis:
§2.1.1, §2.1.6, §8.1, §8.2, §13.0, §15, §15.1.

**JWT**

- **B2-3/1 — Algorithm: ES256 only.** The decoder accepts ES256 and nothing else, so `alg=none` and HS/RS
  algorithm-confusion tokens are rejected.
- **B2-3/2 — Keys from the environment.** Signing key as a PKCS#8 PEM plus its `kid`
  (`PEOPLEHUB_JWT_SIGNING_KEY`, `PEOPLEHUB_JWT_SIGNING_KEY_ID`); previous public keys, keyed by `kid`, remain valid for
  verification during a rotation. The application **fails to start** if the signing key is missing or invalid, and no
  error or log line ever contains key material. **No JWKS endpoint** in `b2-3`. No private key is committed anywhere:
  tests and `docker/smoke.sh` generate throwaway keys at run time.
- **B2-3/3 — Lifetimes.** Access token **15 minutes**; clock-skew allowance **30 seconds**.
- **B2-3/4 — Claims.** `iss`, `aud`, `sub` (employee id), `org` (organization id), `role`, `sid` (refresh-token family
  id), `iat`, `exp`, `jti`; `kid` in the header. **No email or name** in the token.

**Refresh tokens**

- **B2-3/5 — Expiry: 30-day sliding window, 90-day absolute maximum.** Configured internally (application config,
  environment-overridable, validated at startup), **not organization-configurable yet**; organization-level security
  settings come later. So CLAUDE.md §1.5 ("`[confirm]` values become org settings") does not apply to these two values
  for now. The absolute limit is fixed when a login creates the family; each refresh sets the sliding expiry to the
  earlier of now + 30 days and that limit.
- **B2-3/6 — Storage and hashing.** An opaque random 256-bit token (`SecureTokens`); only its **SHA-256** hash is
  stored (`refresh_token.token_hash`). It travels in an `HttpOnly`, `Secure`, `SameSite=Strict` cookie scoped to
  `/api/v1/auth`.
- **B2-3/7 — Rotation.** Every refresh locks the presented row (`SELECT … FOR UPDATE`), revokes it (reason `ROTATED`,
  `replaced_by_id` set) and inserts its successor in the same family, in one transaction. Employee and organization
  must still be `ACTIVE`.
- **B2-3/8 — Reuse detection.** Presenting an already-revoked token revokes **every** token in its family (reason
  `REUSE_DETECTED`), is audited, and returns 401. No grace window: two parallel refreshes with the same token end the
  session, so the client must serialize refreshes.
- **B2-3/9 — Logout.** Revokes the current session's family (reason `LOGOUT`), clears the cookies and always returns
  204 (no cookie, unknown cookie or repeated logout included). Logout-all belongs to `b2-6`.
- **B2-3/10 — Multiple sessions.** Each login is an independent family, with no cap; ending one leaves the others
  alone. `device_label` stays empty until `b2-6` (sessions list) decides its content.
- **B2-3/11 — CSRF and Origin.** Refresh and logout require a double-submit CSRF token: returned in the login/refresh
  response body (the frontend on `app.` cannot read an `api.` cookie, D10) and also set as an `HttpOnly` cookie; the
  `X-CSRF-Token` header must equal the cookie (constant-time comparison). On login, refresh and logout an `Origin`
  header, when present, must equal the configured app origin; otherwise 403. CORS allows only that origin, with
  credentials.

**Authentication behaviour**

- **B2-3/12 — Generic login failure.** Unknown organization, unknown email, wrong password, inactive employee or
  organization, and no password set all return the **same 401 body** ("We couldn't sign you in with those details.").
  One Argon2 verification always runs, against a dummy hash when the account does not exist, to keep timing the same.
  Malformed input is still a 400 validation error.
- **B2-3/13 — Unverified founders cannot log in** and get the same generic 401 (a specific "verify your email" message
  would confirm the account exists); resend-verification (b2-2) remains their path.
- **B2-3/14 — Tenant context.** After ES256 verification, one database check per request confirms the employee and
  organization are `ACTIVE` and the `sid` family is not revoked (not cached). The result is an
  `AuthenticatedPrincipal`; its organization id is the **only** tenant source services use, and the role used for
  authorization is read from the database. Logout and (from `b2-6`) deactivation therefore cut off access tokens
  immediately (D26).
- **B2-3/15 — No lockout enforcement in `b2-3`.** Every attempt (success or failure) is recorded in `login_attempt`;
  lockout/backoff is enforced in `b2-5`, and rate limiting later (as B2-2/3). Client IP is the direct connection
  address; no forwarded headers are trusted until hosting is decided (§19).
- **B2-3/16 — 401/403 as RFC 9457 problems.** Both go through the standard problem body with a correlation id (closes
  the "401/403 must use the same problem body" item owed from B0). A new `FORBIDDEN` problem type covers CSRF, Origin
  and access-denied failures. 401 details are generic (expired, bad signature and revoked look the same), and
  `WWW-Authenticate` carries no `error_description`.
- **B2-3/17 — Audit.** Through `AuditWriter` in the same transaction: `LOGIN_SUCCEEDED`, `LOGOUT`,
  `REFRESH_TOKEN_REUSE_DETECTED` (target `EMPLOYEE`/id, attribute `sessionId`). Normal rotation is not audited. Failed
  logins go to `login_attempt` only (B0-6/16).
- **B2-3/18 — MDC and Sentry.** After authentication `ActorId` becomes the employee id, and the organization id is
  added to MDC as `organizationId`; the Sentry tag allowlist is extended deliberately to include it, with a test
  (resolves the "Logging context (B2)" open item in §2).

**Scope**

- **B2-3/19 — Scope.** Endpoints: `POST /auth/login`, `POST /auth/refresh`, `POST /auth/logout`, and a minimal
  `GET /me` (`id, name, email, role, status, joinDate, organization {name, timezone, onboardingCompleted}`;
  `firstName`/`welcomeSeenAt` and `department` arrive with their own phases). Authorization is only authenticated or
  not: **no RBAC, no roles in `@PreAuthorize`, no permission matrix** (`b3-1`). **Excluded:** MFA, TOTP, recovery codes,
  MFA enrollment/enforcement (`b2-7`, MFA/2), step-up, invitations and activation (`b2-4`), onboarding, password
  reset/change and lockout (`b2-5`), sessions list, logout-all and deactivation (`b2-6`), leave, attendance, payroll.
- **B2-3/20 — RLS moves to `b2-8`.** `b2-3` creates only the tenant context (B2-3/14), shaped so that `b2-8` can set a
  transaction-local tenant setting for RLS policies.
- **B2-3/21 — Stale status lines are fixed separately.** The status lines that do not yet record `b2-1`/`b2-2` as
  merged are corrected in their own small documentation change, never mixed into the `b2-3` implementation.

**Implementation plan (for review; details may be refined in the branch without changing the decisions above)**

- **Commits, each followed by `./mvnw -B -ntp verify`:** (1) Spring Security dependency plus a deny-by-default
  `SecurityFilterChain` (stateless; no form login, HTTP Basic or generated default user; `permitAll` only for
  `/api/v1/public/**`, the three `/api/v1/auth/*` endpoints, the email webhook, `/actuator/health/**` and API docs;
  problem-body 401/403; correlation and actor filters before the security chain), then also `docker/smoke.sh`, per the
  B2 development rules; (2) `V14` migration; (3) JWT keys, issuer, decoder and principal; (4) login, refresh, logout;
  (5) `/me`, OpenAPI bearer scheme and README, then `verify` and `smoke.sh` on a clean stack.
- **`V14__refresh_token_rotation.sql`:** add `refresh_token.organization_id` (backfilled from `employee`, then
  `NOT NULL`); `UNIQUE (organization_id, id)` on `employee` and a composite FK
  `(organization_id, employee_id) → employee (organization_id, id)` so a token can never point across tenants; add
  `revoked_at`, `revoke_reason` (CHECK `ROTATED | LOGOUT | REUSE_DETECTED`, extended by later migrations) and
  `replaced_by_id` (self-FK); CHECKs that `revoked` matches `revoked_at` and a reason is set exactly when revoked. The
  runtime role may insert `organization_id` and update only `revoked, revoked_at, revoke_reason, replaced_by_id`; never
  DELETE or TRUNCATE.
- **Packages:** `security` (filter chain, problem entry point/handler, CORS), `security.jwt` (key set, issuer,
  decoder, JWT-to-principal converter), `security.principal` (`AuthenticatedPrincipal`, accessor, status query), `auth`
  (controller, login/refresh services, token store, cookies, CSRF/Origin guard, properties, DTOs), `profile` (`/me`).
- **Tests:** migration and runtime-role tests for V14, plus the `RuntimePrivilegesTest` inventory (existing
  `RefreshToken*` tests updated for the new column); negative token tests (bad signature, `alg=none`, HS256
  confusion, unknown `kid`, wrong `iss`/`aud`, expired, tampered `org`, revoked session, inactive employee or
  organization), all giving one identical 401; a fail-closed inventory that every non-public endpoint rejects
  unauthenticated calls; CORS, CSRF and Origin; cross-tenant login and `/me`; no PII in logs or Sentry; the full
  register → verify → login → `/me` → refresh → logout flow; identical failure bodies with exactly one Argon2 check
  each; `login_attempt` and audit rows; rotation, reuse, sliding and absolute expiry on a controllable clock; parallel
  refresh; logout idempotence; independent sessions. Unique values in every test (B2 development rules).
- **CI:** `smoke.sh` gains two checks: the backend refuses to start without a signing key, and `GET /api/v1/me`
  without a token returns a 401 problem body. Compose requires the key (`${…:?}`); `.env.example` lists the names
  blank.

### B2-4 decisions (owner-approved 2026-09-23; recorded here so they survive a session or repo reset)

Cite as "B2-4/O4" and so on (never a bare `D#`, which is the spec's). Scope:
`feature/b2-4-invite-activation-admin-invite` — Employee invitations (Admin or Super Admin), direct Admin invitations
(Super Admin), resend and revoke, public preview and acceptance (the invitee sets their own password), the invitation
email through the outbox, audit rows, and the one-time welcome state. **Implemented and merged (PR #26).** Spec basis:
D24, D25, §2.1.5, §3.3, §8.2, §9.1, §10.2, §10.3, §12.1, §13.0, §15.1.

- **B2-4/O1 — The employee row is created at invite time** with `status = INVITED`, the invitation's role and no
  password (D25). Accepting the invitation activates that row; no second identity is ever created.
- **B2-4/O2 — Employee code is optional in the invite request.** When absent, the code is generated with
  `EmployeeCodes` (as registration does); a duplicate code inside the organization is a 409.
- **B2-4/O3 — Invitation lifetime: 7 days**, an internal setting (environment-overridable), not
  organization-configurable, the same treatment as B2-3/5. Expiry is checked against the injected `Clock`.
- **B2-4/O4 — Preview** returns, for a valid token only: the organization's display name, the intended role, the
  invitee's own name and email, and the expiry. An unknown, expired, used or revoked token all get **one identical
  generic 404** ("This invitation is invalid or has expired."). Preview never changes anything, so a mail scanner that
  follows the link cannot consume it (the D8 principle).
- **B2-4/O5 — Acceptance activates only (204).** It opens no session: the invitee then signs in through the normal
  login (B2-3), so there is one login path.
- **B2-4/O6 — `V15` hardening:** `UNIQUE (token_hash)` on `employee_invitation`; a CHECK that an invitation is never
  both consumed and revoked; a composite foreign key `(organization_id, inviter_employee_id)` →
  `employee (organization_id, id)` so an inviter can never belong to another organization.
- **B2-4/O7 — Inviting an email that already exists in the organization:** an `ACTIVE`, `DEACTIVATED` or other
  non-invited employee is a **409** (an in-tenant signal to the Admin, not cross-tenant enumeration). An `INVITED`
  employee with no open invitation gets **a new invitation on the existing row** (same role only; a different role is a
  409). An open invitation already existing is a 409 (use resend).
- **B2-4/O8 — Resend and revoke permissions:** an Admin may resend or revoke **Employee** invitations only; a Super Admin
  may resend or revoke any. An invitation id from another organization is a **404**, never a 403 (D22). An expired but
  unconsumed, unrevoked invitation can be resent; a consumed or revoked one cannot (409).
- **B2-4/O9 — Promoting an existing Employee to Admin is deferred to `b2-7`**: it requires step-up authentication (§3.3,
  §8.3), which `b2-7` builds.
- **B2-4/O10 — Invitation email:** type `EMPLOYEE_INVITED` (the B1 template, whose text is updated). The payload holds
  only plain token values (B0-6/11): `appName`, `firstName` (generic greeting when the name is not a plain token, as
  registration does), `organizationLoginKey` (the invitee needs it to sign in), `role` and `inviteCode` (the raw
  token). No link is sent yet. **Future frontend URL support:** the payload already carries everything a frontend
  invitation link (`/invite/{inviteCode}`, spec §10.4.7) needs, so a link can be added later from a frontend URL setting
  without changing the invitation flow or its data; implementation is **not blocked** on that setting, and none is
  added now.
- **B2-4/O11 — Resend and revoke paths:** `POST /admin/invitations/{id}/resend` and `POST /admin/invitations/{id}/revoke`
  (spec §13.0 only says they are tenant-local and permission-checked).
- **B2-4/O12 — The welcome state is included:** `GET /me` gains `firstName` (D17: the first word of `name`, or the
  whole name when it has none) and `welcomeSeenAt`; `POST /me/welcome/ack` sets `welcome_seen_at` once (idempotent,
  204), per §10.2 and the §22.1 launch gate "invitation → private password → welcome".
- **B2-4/O13 — No invitation list endpoint** in `b2-4`; listing belongs with the directory and employee CRUD (`b3-3`).
  Resend and revoke work on the id the invite response returns.
- **B2-4/O14 — In-app notifications ("employee invited/activated", §9.1) are deferred:** there is no Admin
  notification surface yet. Audit rows record every invitation action now.
- **B2-4/O15 — Rate limiting on the public preview and accept endpoints: recorded, not built** (as B2-2/3).

**Authorization in `b2-4`** is a minimal service-layer role check on the principal's database role (Admin or Super Admin
for Employee invitations, Super Admin for Admin invitations), not the `b3-1` permission matrix. **Deferred to their
planned branches, not built in `b2-4`:** MFA and any MFA gate for invited Admins (`b2-7`, MFA/1–MFA/7; the spec's
"Admin invitees complete MFA" text is §15 item 15), promotion (`b2-7`), password reset and lockout (`b2-5`), deactivation
and revoking pending invitations on deactivation (`b2-6`), RLS (`b2-8`), the permission matrix (`b3-1`), departments
(`b3-2`), bulk import (`b3-5`), notifications and rate limiting.

### B2-5 decisions (owner-approved 2026-09-24; recorded here so they survive a session or repo reset)

Cite as "B2-5/P4" and so on (never a bare `D#`, which is the spec's). Scope:
`feature/b2-5-password-policy-lockout-reset` — the password policy including a breached-password check, failed-login
counting and lockout with backoff, forgot/reset password, change password, and the audit rows and enumeration tests
that go with them. **Implemented and merged (PR #29).** Spec basis: §8.2, §13.0, §15 (items 3, 10),
§15.1, §17 (B2 row).

- **B2-5/P1 — Breached-password check: an offline list.** A licensed common/breached-password list is bundled with the
  application, loaded at startup, and checked behind the existing `PasswordSecurityValidator` (B2-2/2). No outbound
  call, so no availability or privacy dependency on a third-party service. A k-anonymity API (for example HaveIBeenPwned)
  can be added later behind the same interface without changing any caller.
- **B2-5/P2 — Password rules: length plus the breached list, no composition rules.** Minimum 12 characters (unchanged),
  maximum 128, the existing context terms (organization, name, email local part) and the breached list; no "must
  contain a digit/symbol/uppercase" rules (current NIST SP 800-63B guidance). This is how `b2-5` reads the spec's
  "minimum length + complexity" (§8.2). One policy everywhere a password is set: registration, invitation acceptance,
  reset and change.
- **B2-5/P3 — Lockout state is stored in the database.** Per-account state lives on `employee` (new columns
  `failed_login_count` and `locked_until`, with their own runtime grants in a new migration). Not Redis: a Redis flush
  or outage must not reset or lose lockout state (the same reasoning as B0-5/S1). `login_attempt` stays forensic: it
  stores what was typed and cannot reliably identify one account, and it keeps recording each attempt's address.
- **B2-5/P4 — Per-account backoff values (`[confirm]`, internal settings, not organization-configurable, like
  B2-3/5).** After 5 consecutive failures the account is locked for 1 minute, doubling on each further lock up to
  30 minutes; the count resets on a successful login or a password reset. Environment-overridable.
- **B2-5/P5 — Per-IP enforcement is deferred.** `b2-5` enforces per-account lockout only. The client address is the
  direct peer (B2-3/15); behind a load balancer every user would share one address, so per-IP enforcement could lock
  everyone out. Per-IP lockout (its thresholds, and whether it is a lockout or a rate limit) is decided together with
  the hosting and trusted-proxy decision (§19), not in `b2-5`. `login_attempt` already records the address of every
  attempt, so nothing is lost in the meantime.
- **B2-5/P6 — Reset token in the request body.** `POST /auth/reset-password` takes `{token, password, confirmPassword}`
  (spec §13.0 gives no path). The reset email carries the token as `resetCode`, the same code-only pattern as
  invitations (B2-4/O10); a frontend reset link can be added later from a frontend URL setting without changing the
  flow or its data.
- **B2-5/P7 — Forgot-password throttle in the database.** At most one reset email per account per 5 minutes and 5 per
  day, derived from `password_reset_token` rows; the public answer is identical whether or not an email was sent.
  General request rate limiting (Bucket4j + Redis) stays in `b13-1`, as B2-2/3, B2-3/15 and B2-4/O15 already recorded.
- **B2-5/P8 — Reset token: 30 minutes, single use.** Stored only as a hash, in a new `password_reset_token` table
  (tenant-bound, composite foreign key to `employee (organization_id, id)`, the same pattern as V14/V15). Requesting a
  new token invalidates older unused ones for that account. Internal setting.
- **B2-5/P9 — Change password is included.** `POST /me/password` with `{currentPassword, newPassword,
  confirmPassword}`: requires the current password, applies the same policy, revokes the employee's **other** sessions
  and keeps the current one (§8.2). The sessions list, revoke-one and logout-all remain in `b2-6`.
- **B2-5/P10 — No "your password was changed/reset" notification email or in-app notification** in `b2-5`; deferred with
  the notification work (as B2-4/O14). The reset email itself is part of the flow and is built. The B1-3 in-app type
  `PASSWORD_RESET` stays unused for now.
- **B2-5/P11 — Only active accounts can reset.** Forgot-password sends nothing for an `INVITED`, `PENDING_VERIFICATION` or
  `DEACTIVATED` employee, or an organization that is not `ACTIVE`, and gives the identical public answer. An unverified
  founder uses resend-verification (`b2-2`).
- **B2-5/P12 — Forgot-password timing: small difference accepted and recorded.** A known active account does extra
  database work (a token row and an outbox row). That difference is accepted; the wording and status of the answer are
  identical and tested (§15.1). Making the two paths do identical work is not done in `b2-5`.
- **B2-5/P13 — A completed reset clears the lockout**, since it proves control of the account's email. A reset also
  revokes **all** of the employee's sessions (§8.2); two new `refresh_token.revoke_reason` values, `PASSWORD_RESET` and
  `PASSWORD_CHANGED`, are added by a forward migration (V14's CHECK allows only `ROTATED`, `LOGOUT`, `REUSE_DETECTED`).

**Behaviour that holds across all of the above:** a locked account answers a login exactly like a wrong password (same
401, same body, same dummy-hash timing), so lockout never reveals that an account exists. Audit rows
(`PASSWORD_RESET_REQUESTED`, `PASSWORD_RESET_COMPLETED`, `PASSWORD_CHANGED`, `ACCOUNT_LOCKED`) are written only when a
real organization has been resolved (B0-6/16) and hold ids only, never passwords, tokens or emails; failed logins stay in
`login_attempt` only.

**Deferred to their planned branches, not built in `b2-5`:** per-IP lockout (with the hosting and trusted-proxy
decision, §19; B2-5/P5); request rate limiting with Bucket4j (`b13-1`); sessions
list, revoke-one, logout-all and deactivation (`b2-6`); MFA, step-up and promotion (`b2-7`; a reset must then not bypass
an organization's MFA requirement); RLS (`b2-8`); Admin-triggered resets and Admin-set passwords (never; D24);
notifications; the frontend reset page (F1).

### B2-6 decisions (owner-approved 2026-09-24; recorded here so they survive a session or repo reset)

Cite as "B2-6/4" and so on (never a bare `D#`, which is the spec's). Scope:
`feature/b2-6-sessions-deactivation-revoke` — the signed-in employee's sessions list, revoke-one and revoke-others, and
employee deactivation and reactivation with immediate revocation of access. **Implemented and merged (PR #32).** Spec
basis: D26, §2.1.7, §3.2, §3.3, §8.2, §13.0, §13 (Security and Admin — employees rows), §15.1, §22.1.

**Sessions**

- **B2-6/1 — A session is a refresh-token family.** Its id is the family id (the access token's `sid`, B2-3/4). It is
  *active* while its family has a token that is not revoked and whose sliding expiry has not passed. Nothing new is
  stored to track sessions.
- **B2-6/2 — `GET /me/sessions`**, the caller's own active sessions only (employee and organization from the token). A
  list endpoint, so it is paginated like every other (`PageQuery`/`PageResponse`, size default 20, max 100; §6 of this
  file). Each item: `sessionId`, `current` (true for the calling session), `deviceLabel`, `createdAt` (sign-in time),
  `lastUsedAt` (last refresh), `expiresAt`, `absoluteExpiresAt`. Sortable by `createdAt` and `lastUsedAt`; default
  `lastUsedAt,desc`. No token, token hash or IP is ever returned.
- **B2-6/3 — Device label: coarse browser and operating system.** `refresh_token.device_label` has stayed empty since
  B2-3/10. At login, a coarse label is derived from the `User-Agent` by a few built-in rules, browser family and
  operating system only (for example "Chrome on Windows"), at most 64 characters, with a safe fallback such as "Unknown
  device" when nothing matches; it is copied to each rotated token. Never the raw `User-Agent` and never the IP
  (fingerprinting and personal-data minimisation). No external dependency.
- **B2-6/4 — `DELETE /me/sessions/{sessionId}`** revokes one of the caller's own sessions (all tokens of that family).
  Revoking the current session is allowed and acts as a logout from this device. A session id that is not one of the
  caller's active sessions (unknown, already ended, another employee's, another organization's) is one 404. 204.
- **B2-6/5 — `POST /me/sessions/revoke-others`** is the spec's "revoke all others" (§8.2) and the logout-all that B2-3/9
  deferred: every session of the caller except the calling one ends. 204, with nothing to revoke included. No separate
  "log out everywhere including here": that is revoke-others followed by the existing logout.
- **B2-6/6 — New revoke reasons**, added to `refresh_token.revoke_reason` by a forward migration (the V14/V16 CHECK
  pattern): `SESSION_REVOKED` (revoke-one and revoke-others) and `DEACTIVATED`. No new columns and no new grants: the
  runtime role can already update `revoked`, `revoked_at` and `revoke_reason`.

**Deactivation and reactivation**

- **B2-6/7 — Endpoints:** `POST /admin/employees/{id}/deactivate` and `POST /admin/employees/{id}/reactivate` (§13.0,
  §13). 204. Deactivation is not in the spec's step-up list (§8.3), so no step-up.
- **B2-6/8 — Who may act on whom** (service-layer check on the caller's database role, B2-4's minimal pattern; the full
  matrix is `b3-1`):

  | Caller | Employee | Admin | Super Admin | Themselves |
  |---|---|---|---|---|
  | Admin | allowed | 403 | 403 | 403 |
  | Super Admin | allowed | allowed | allowed (another Super Admin) | 403 |

  An id from another organization, or an unknown id, is a **404**, never a 403 (D22, §15.1). A 403 is only ever given
  inside the caller's own organization, where the target's existence is not a secret (the B2-4/O8 treatment).
- **B2-6/9 — The last Super Admin cannot be deactivated.** Since nobody may deactivate themselves (B2-6/8) and only a
  Super Admin may deactivate a Super Admin, deactivation can never remove the last active one. The explicit
  last-Super-Admin guard for demotion and tombstoning stays in `b3-4` (§3.3); b2-6 adds a test proving the invariant.
- **B2-6/10 — Which states.** `ACTIVE` and `INVITED` employees can be deactivated; `PENDING_VERIFICATION` (an
  unverified founder), `DEACTIVATED` and `DELETED_TOMBSTONE` cannot (409). Only `DEACTIVATED` can be reactivated (409
  otherwise). Reactivation returns the employee to `ACTIVE` if they have a password, or to `INVITED` if they never
  accepted their invitation (an Admin then resends it, B2-4/O8).
- **B2-6/11 — What deactivation does, in one transaction** (the employee row is locked first):
  1. `status = DEACTIVATED`. The request body may carry an optional `exitDate`, stored as `exit_date`; it cannot be in
     the future (a 400; "today" is the organization's timezone). When it is omitted, `exit_date` stays empty: the
     server never fills in a date on its own.
  2. Every refresh-token family of the employee is revoked (`DEACTIVATED`). Access tokens stop working at the next
     request through the existing per-request status check (B2-3/14), so no token outlives the deactivation.
  3. An open invitation of an `INVITED` employee is revoked (the item B2-4 left to b2-6).
  4. Unused password-reset codes are invalidated (already unusable while the account is not active, B2-5/P11; this
     makes it permanent).
  5. `EMPLOYEE_DEACTIVATED` is audited.

  Kept unchanged: the password hash, the lockout counters, invitations the person sent (organization-level, still
  valid), and every historical row (attendance, leave, approvals and audit arrive later and point at the same row).
- **B2-6/12 — A concurrent refresh cannot outlive a deactivation.** Required behaviour: a refresh and a deactivation
  running at the same time can never leave a usable session once the deactivation has completed. The risk is a refresh
  that read `ACTIVE` just before the deactivation committed and inserts a successor token the deactivation's revocation
  never saw; that token would be unusable while deactivated, but a later reactivation would revive it. The
  implementation chooses the correct database locking approach, and a parallel test proves the behaviour.
- **B2-6/13 — What reactivation does:** status as B2-6/10, `exit_date` cleared, `EMPLOYEE_REACTIVATED` audited. No
  session comes back: the person signs in again. Password, role and lockout counters are as they were (§3.3 "restores
  prior access exactly as it was"). A future MFA policy still applies at the next sign-in (`b2-7`).
- **B2-6/14 — Login, refresh and reset after deactivation** need no new code: login and refresh already require
  `ACTIVE` and answer with the same generic 401 (B2-3/12); forgot-password already sends nothing (B2-5/P11). b2-6 adds the
  tests that prove each one for a deactivated Employee, Admin and Super Admin.

**Audit**

- **B2-6/15 — Events**, through `AuditWriter` in the same transaction, ids and counts only (B0-6/8, B0-6/11):
  `EMPLOYEE_DEACTIVATED` (actor the Admin/Super Admin; target `EMPLOYEE`/id; attributes `role`, `revokedSessions`,
  `invitationRevoked`), `EMPLOYEE_REACTIVATED` (attributes `role`, `status`), `SESSION_REVOKED` (actor and target the
  employee; attribute `sessionId`; also when it is the current session) and `OTHER_SESSIONS_REVOKED` (attribute
  `revokedSessions`). Failed attempts (403, 404, 409) are not audited.

**Tenant isolation**

- **B2-6/16 — Tenant scope only from the token.** Every query is qualified by the caller's organization and, for
  `/me/sessions`, the caller's employee id; no organization or employee id is ever taken from the request except the
  path id of the target employee, which is resolved inside the caller's organization. Tests cover another
  organization's employee id and session id (404, nothing changed) and the same email in two organizations.

**Deferred to their planned branches, not built in `b2-6`:** closing an open attendance session with `DEACTIVATION`
(B4, §4.3); revoking paired device authorization (B5); reassigning pending approvals and cancelling the person's
pending requests (B7, §6.5, §7.2); cutting an open SSE stream (no production SSE endpoint exists yet; when one is built it
must end a deactivated employee's stream, §22.1); "admin deactivated/reactivated" notifications (§9.1, as B2-4/O14);
the employee directory and employee CRUD (`b3-3`); the last-Super-Admin guard for demotion and tombstoning (`b3-4`);
MFA and step-up (`b2-7`); RLS (`b2-8`); rate limiting (`b13-1`).

**Spec note (no conflict, recorded):** spec §17 lists "deactivation/revocation" under B2 and "employee CRUD/deactivate"
under `b3-3`. Reading used here: `b2-6` builds deactivation, reactivation and the revocation; `b3-3` builds employee
CRUD and the directory and reuses them.

### B2-7 decisions (owner-approved 2026-09-24; recorded here so they survive a session or repo reset)

Cite as "B2-7/4" and so on (never a bare `D#`, which is the spec's). Scope: `feature/b2-7-mfa-stepup-onboarding` — the
organization MFA policy, TOTP enrollment and sign-in challenge, recovery codes, MFA reset, step-up authentication,
Employee → Admin promotion, and the founder's onboarding completion. **Implemented and merged (PR #36, checkpoints
C1-C7).** Builds on the owner's "MFA policy decisions" (MFA/1-MFA/7). Spec basis: D6 (as amended), D25, §2.1.3,
§2.1.4, §2.1.5, §3.2, §3.3, §8.2, §8.3, §11, §12, §12.1, §13.0, §15.1, §22.1. The implementation-time decisions that
refine this block are "B2-7 implementation decisions" below.

**Gate before coding (met):** the spec's mandatory-MFA text was corrected by its own `docs:` spec PR (PR #35), merged
before the implementation branch was created (§15 item 15).

**MFA policy and who needs MFA**

- **B2-7/1 — MFA is policy-controlled; the Super Admin sets the organization's policy.** MFA is recommended but not
  globally mandatory: whether it is enforced, and for whom, is the organization's policy. MFA is optional only during
  founder/Super Admin registration: the founder registers the organization and becomes its Super Admin without any
  MFA step (MFA/1). After organization setup the Super Admin controls the organization's MFA enforcement policy
  (MFA/3-MFA/5), default `DISABLED`:
  - `DISABLED`: nobody is required; no enrollment offered.
  - `OPTIONAL`: nobody is required; users may enable MFA voluntarily (reminders encourage it, MFA/7).
  - `REQUIRED_FOR_ADMINS`: Admin MFA required — every Admin and Super Admin must enroll MFA before any Admin
    operation; Employees may enable it voluntarily.
  - `REQUIRED_FOR_ALL`: Admin and Employee MFA required — every user must enroll MFA before accessing any protected
    feature.
  - `REQUIRED_FOR_SELECTED_USERS`: required for the people a Super Admin selects; others may enable it voluntarily.

  "Before" is enforced at sign-in: a required person who has not enrolled gets no session until enrollment is
  confirmed (B2-7/9). Storage: a new `organization.mfa_policy` column (CHECK on the five values, default `DISABLED`)
  and a new employee-level `employee.mfa_required` flag that supports `REQUIRED_FOR_SELECTED_USERS`; not the spec's
  `mfa_required_roles` setting, and not the generic org-settings store, which arrives in `b3-6`. All five values ship
  in `b2-7`.
- **B2-7/2 — Anyone enrolled is always challenged**, whatever the policy: enrolling is a promise that the password
  alone no longer signs this person in. `DISABLED` hides enrollment but never switches off an existing enrollment;
  that takes an explicit disable or reset (B2-7/12).
- **B2-7/3 — Changing the policy** is Super Admin only, needs step-up (B2-7/14; spec §8.3 "changing security
  settings") and is audited (`MFA_POLICY_CHANGED`). A change that newly requires MFA for people not enrolled ends
  their sessions (new revoke reason `MFA_REQUIRED`), so the requirement applies at their next sign-in, with no grace
  period. The Super Admin making the change is exempt from losing their own current session; if the new policy
  requires MFA for them, they enroll before their next MFA-protected action (B2-7/15) and at their next sign-in at the
  latest.

**Enrollment**

- **B2-7/4 — When enrollment happens.**
  - *Voluntarily*, any time the policy is not `DISABLED`: `POST /me/mfa/enroll` then `POST /me/mfa/confirm` from a
    signed-in session. This is also how someone the policy requires to have MFA enrolls before an MFA-protected action
    (B2-7/15).
  - *Required at sign-in*: when the policy requires MFA for someone not enrolled, the password step creates no
    session; it returns an enrollment challenge (B2-7/9), and the session is created only after enrollment is
    confirmed. This applies the same way to the founder, an invited Employee or Admin, and a promoted Admin.
  - *Founder setup*: MFA is not part of registration or verification (MFA/1). The founder becomes Super Admin first;
    the onboarding security step (B2-7/21) then lets them choose the policy and, if they wish, enroll.
  - *Invitation acceptance* opens no session (B2-4/O5), so it asks for no MFA; the first sign-in applies the policy.
  - *Promotion* does not require prior enrollment. When the policy requires MFA for Admins, the promoted person
    enrolls at their next sign-in, before any Admin operation (B2-7/18).
- **B2-7/5 — Authenticators: TOTP only, plus recovery codes** (MFA/6). RFC 6238, HMAC-SHA1, 6 digits, 30-second step,
  one step of drift either way; implemented with the JDK's HMAC, no dependency (the `WebhookSignatureVerifier`
  convention). A code's time step is remembered (`employee.mfa_totp_last_step`), so the same code cannot be used twice.
  The backend returns an `otpauth://` URI and the frontend draws the QR code. WebAuthn/passkeys, SMS and email codes
  are not built (deferred, see the end of this block).
- **B2-7/6 — Enrollment flow.** `enroll` generates a new 160-bit secret, stores it encrypted with `mfa_enabled = false`
  (pending), and returns the secret and URI once. `confirm` with a valid code sets `mfa_enabled = true`, records
  `mfa_enrolled_at`, creates 10 recovery codes and returns them once (B2-7/8). Enrolling again while enrolled needs
  step-up and replaces the secret and every recovery code; the new secret waits in `mfa_totp_pending_secret` (V23)
  until confirmed (B2-7/I9). An unconfirmed pending secret is simply overwritten by the next `enroll`.
- **B2-7/7 — Secret storage: AES-256-GCM, never raw.** The secret is encrypted in the application with a key from
  the environment (`PEOPLEHUB_MFA_ENCRYPTION_KEY`, 32 bytes base64, with a key id for rotation,
  `PEOPLEHUB_MFA_ENCRYPTION_KEY_ID`, plus previous keys for decryption only, the JWT key pattern B2-3/2). Each value is
  `keyId:base64(nonce || ciphertext || tag)` with a random 96-bit nonce, and the organization and employee ids as
  associated data, so a ciphertext copied onto another account fails to decrypt. Stored in the existing
  `employee.mfa_totp_secret` (V9, "ciphertext only"). The application refuses to start without a valid key, even
  while every organization is `DISABLED` (fail fast, like the JWT key; Compose, `docker/smoke.sh` and CI get the new
  variable). No key material or secret ever reaches a log, an error, an audit row or a response other than the
  one-time enrollment answer.
- **B2-7/8 — Recovery codes: 10 single-use codes**, each 16 base32 characters (80 bits) shown as four groups of four,
  returned once and stored only as SHA-256 hashes (the `SecureTokens` discipline; 80 bits need no slow hash).
  `mfa_recovery_code` gains a required `organization_id` with a composite foreign key to
  `employee (organization_id, id)` (the V14-V17 tenant pattern). Regenerating them (step-up) replaces all ten.
  Responses carrying a secret or codes are sent with `Cache-Control: no-store`.

**Sign-in with MFA**

- **B2-7/9 — Challenge after the password.** When the password is right and the person is enrolled (or must enroll),
  `POST /auth/login` answers 200 with `{mfaRequired: "CHALLENGE" | "ENROLL", challengeToken}` instead of a session:
  no refresh cookie and no access token. The challenge token is random, stored only as a hash in a new
  `mfa_challenge` table (organization, employee, purpose, expiry 5 minutes for a challenge and 10 for enrollment,
  consumed, attempts), single use, and bound to the device label and IP the password step saw.
  - `POST /auth/mfa/challenge {challengeToken, code | recoveryCode}` completes the sign-in and creates the session.
  - `POST /auth/mfa/enroll` and `/auth/mfa/enroll/confirm {challengeToken, …}` run B2-7/6 for a required enrollment and
    then create the session.
  - Every failure of the password step is still the one generic 401 (B2-3/12); the MFA step only starts after a
    correct password, so its answers reveal nothing an attacker without the password could use.
- **B2-7/10 — Wrong codes.** A challenge allows 5 wrong codes, then it is invalidated and the person signs in again.
  Each wrong code also counts toward the per-account lockout (B2-5/P4, R5 pattern). Failed attempts are not audited
  (B2-3/17 pattern); the lockout is (`ACCOUNT_LOCKED`).
- **B2-7/11 — Lost device.** A recovery code signs in once instead of a TOTP code (audited
  `MFA_RECOVERY_CODE_USED`, with the number left); the person should then enroll a new device or regenerate codes. With
  no device and no codes left, an authorized person resets their MFA (B2-7/12). A password reset never removes MFA:
  after a reset the next sign-in is still challenged (B2-5 deferral kept). There is no self-service reset by email,
  because email alone would bypass the second factor.
- **B2-7/12 — MFA reset of another person** (spec §3.2, §8.3): an Admin may reset an Employee's MFA; a Super Admin may
  reset an Admin's or an Employee's. Nobody resets a Super Admin's MFA or their own through this action (403); a Super
  Admin who has lost their factor uses a recovery code or, failing that, the break-glass procedure (B2-7/13). Step-up
  required; target resolved inside the caller's organization (404 otherwise, the B2-6/8 treatment). It clears the
  secret and every recovery code, sets `mfa_enabled = false`, ends every session of the target (new revoke reason
  `MFA_RESET`) and is audited (`MFA_RESET`). The person enrolls again at next sign-in if the policy requires it.
  **Disabling one's own MFA** is refused (409) while the policy requires MFA for that person; otherwise it is allowed
  after a successful step-up (`MFA_DISABLED`).
- **B2-7/13 — Last Super Admin locked out.** If the only Super Admin loses their device and their codes, nobody inside
  the organization can reset them. The spec's break-glass runbook (§3.3: server access, audited) covers it; it is
  documented, not an API, and is deferred (see the end of this block). The onboarding security step warns while fewer
  than two Super Admins exist (§3.3).

**Step-up authentication**

- **B2-7/14 — What needs step-up.** In `b2-7`: Employee → Admin promotion, MFA reset of another person, changing the
  organization's MFA policy, disabling one's own MFA, re-enrolling, and regenerating recovery codes. The mechanism is
  reusable; the spec's other step-up actions adopt it when they are built: deleting an Admin (`b3-4`), bulk import
  (`b3-5`), device pairing (B5), unlocking a month and exporting the whole organization (B11). Deactivation stays
  without step-up (B2-6/7).
- **B2-7/15 — How it is proven.** `POST /me/step-up {password, code?}` (this settles MFA/"Open for b2-7"):
  - a caller with MFA enabled proves the password **and** a TOTP or recovery code;
  - a caller the organization's policy requires to have MFA, but who has not enrolled, gets **403** with a new problem
    type `urn:peoplehub:problem:mfa-enrollment-required`, enrolls first (B2-7/4), then steps up with both;
  - a caller for whom MFA is not required and who has not enrolled proves the password alone.

  A wrong password or code counts toward the per-account lockout. Success is recorded **server-side against the
  calling session**, in a new `session_step_up` row (organization, employee, session family id, verified at, method),
  not in a token claim: it cannot outlive the session, needs no new access token, and ends with the session.
- **B2-7/16 — Five minutes** (spec §8.3), from the verification's database time. A step-up protected endpoint without a
  fresh step-up answers **403** with a new problem type `urn:peoplehub:problem:step-up-required`, so the client can
  prompt and retry. Audited: `STEP_UP_VERIFIED` (method `PASSWORD`, `PASSWORD_AND_TOTP` or
  `PASSWORD_AND_RECOVERY_CODE`).

**Employee → Admin promotion**

- **B2-7/17 — `POST /super-admin/employees/{id}/promote-admin`** (spec §13.0). Super Admin only (spec §3.2, §3.3, D25);
  an Admin gets 403. Checks in the B2-6 order: 404 (unknown or another organization's id), 403 (not a Super Admin, or
  the caller themselves), 403 `step-up-required`, then 409 unless the target is an `ACTIVE` `EMPLOYEE`. MFA
  enrollment is not a precondition of promotion. An `INVITED` person is invited directly as Admin instead (B2-4); an
  Admin or Super Admin is already privileged.
- **B2-7/18 — What promotion does:** role becomes `ADMIN`; every session of the target ends (new revoke reason
  `ROLE_CHANGED`), so the new role takes effect at their next sign-in. If the organization's policy requires MFA for
  Admins (`REQUIRED_FOR_ADMINS`, `REQUIRED_FOR_ALL`, or they are selected) and they have not enrolled, that sign-in is
  an enrollment challenge (B2-7/9): they complete MFA enrollment before any Admin operation. Already enrolled, their MFA
  is challenged (B2-7/2). Audited `EMPLOYEE_PROMOTED` (attributes `fromRole`, `toRole`). The per-request check already
  reads the role from the database (B2-3/14).
- **B2-7/19 — Not in `b2-7`:** demotion (Admin → Employee) and granting Super Admin, which need the explicit
  last-Super-Admin guard and ownership transfer (§3.3), move to `b3-1`/`b3-4`. Promotion never reduces the number of
  Super Admins, so no guard is needed for it.

**Onboarding**

- **B2-7/20 — First sign-in after an invitation:** password → MFA enrollment only if the policy requires it (B2-7/9) →
  session → the existing one-time welcome state (B2-4/O12). With no requirement, the session starts directly and
  `GET /me` carries an `mfa` object (`enabled`, `required`, `policy`, `showReminder`); the reminder is decided on the
  server (B2-7/27) and never blocks (MFA/7).
- **B2-7/21 — Founder onboarding in `b2-7`:** `POST /organization/onboarding/complete` (Super Admin only, idempotent,
  sets `organization.onboarding_completed_at`, audited `ORGANIZATION_ONBOARDING_COMPLETED`) and the security step (the
  MFA policy and the founder's own enrollment). The other setup steps of §2.1.4 (work schedule, attendance safeguards,
  leave basics) need the org-settings API and arrive with `b3-6`; until then the frontend skips them. Choosing the
  policy is a step-up action (B2-7/14); a founder without MFA steps up with their password (B2-7/15).
- **B2-7/22 — Incomplete enrollment.** A required enrollment that is abandoned leaves no session and no enabled MFA;
  its challenge expires (10 minutes) and the next sign-in starts again. There is no access at all before a required
  enrollment is confirmed, and full access without one when the policy does not require it.

**Security requirements**

- **B2-7/23 — Tenant isolation.** Every query is qualified by the organization and employee from the token, or, for
  the challenge endpoints, from the challenge row the hashed token finds. Another organization's employee id is 404
  for promotion and MFA reset. Secrets are bound to their account (B2-7/7), and recovery codes and challenges to their
  organization by foreign key.
- **B2-7/24 — Nothing secret in logs, errors or audit rows:** no TOTP secret, `otpauth://` URI, code, recovery code,
  challenge token, password or encryption key, and no email address in audit details (B0-6/8). Tests assert on whole
  captured logs and whole response bodies, as in b2-5.
- **B2-7/25 — Audit events:** `MFA_POLICY_CHANGED`, `MFA_ENROLLED`, `MFA_DISABLED`, `MFA_RESET`,
  `MFA_RECOVERY_CODE_USED`, `MFA_RECOVERY_CODES_REGENERATED`, `STEP_UP_VERIFIED`, `EMPLOYEE_PROMOTED`,
  `ORGANIZATION_ONBOARDING_COMPLETED`, and (added during implementation, B2-7/I12) `MFA_SELECTION_CHANGED`, all with
  ids, counts and fixed values only. Successful sign-in stays `LOGIN_SUCCEEDED`, written when the MFA step completes.
- **B2-7/26 — Rate limiting** is still `b13-1`; until then challenges are limited per challenge (B2-7/10) and per
  account (lockout).

**MFA reminders**

- **B2-7/27 — Reminders are decided and remembered on the server.** When the organization's policy does not require
  MFA for a person but recommends it, the system may remind them to enable it (MFA/7).
  - *Who is eligible:* someone not enrolled and not required, while enrollment is offered: every such person under
    `OPTIONAL`, and the people not covered by the requirement under `REQUIRED_FOR_ADMINS` (Employees) and
    `REQUIRED_FOR_SELECTED_USERS` (those not selected). Nobody is reminded under `DISABLED`, and a required person is
    never "reminded": they meet the enrollment requirement at sign-in instead (B2-7/9).
  - *Frequency is server-side:* an internal setting, `peoplehub.mfa.reminder-interval` (default `P7D`, `[confirm]`,
    environment-overridable, not organization-configurable, like B2-3/5).
  - *Dismissal is server-side:* `POST /me/mfa/reminder/dismiss` (the caller's own, from the token; 204, idempotent)
    stores the time in a new `employee.mfa_reminder_dismissed_at` column, from the injected `Clock`. Not audited: it
    changes no security state.
  - *The server decides when to show it:* `GET /me` returns `mfa.showReminder = true` only while the person is
    eligible and has never dismissed it or dismissed it at least one interval ago. Whether to show a reminder comes
    only from that flag, and a dismissal is recorded only through the endpoint; clients hold no reminder state, so
    clearing browser data, another browser or another device cannot reset the timing. How the reminder looks is
    frontend work (F1), not part of `b2-7`.
  - Enrolling ends eligibility; an MFA disable or reset (B2-7/12) makes the person eligible again under the same
    timing.

**Migrations (as built):** `V19` `organization.mfa_policy`, `employee.mfa_required`, `mfa_enrolled_at`,
`mfa_totp_last_step`, `mfa_reminder_dismissed_at`, the `employee.role` UPDATE grant and the revoke reasons
`MFA_REQUIRED`, `MFA_RESET`, `ROLE_CHANGED`; `V20` `mfa_recovery_code.organization_id` with its composite foreign key
and `invalidated_at`; `V21` `mfa_challenge`; `V22` `session_step_up`; `V23` `employee.mfa_totp_pending_secret` (not in
the original plan, owner-approved in C5, B2-7/I9). Each with per-table runtime grants and the `RuntimePrivilegesTest`
inventory.

**Deferred, not built in `b2-7`:** WebAuthn/passkeys; SMS or email codes (never planned); "remember this device"; a
configurable grace period for newly required MFA; demotion and granting Super Admin, and ownership transfer
(`b3-1`/`b3-4`); the break-glass runbook (with B13 operations work); the other §2.1.4 setup steps (`b3-6`); the other
step-up actions in their own phases (B2-7/14); the security dashboard and MFA coverage reports; MFA notifications
("your MFA was reset", "new device enrolled"); request rate limiting (`b13-1`); RLS (`b2-8`); the frontend screens
(F1).

### B2-7 implementation decisions (owner-approved 2026-09-25 and 2026-09-26, checkpoints C1-C7; merged in PR #36)

Cite as "B2-7/I4" and so on. These refine "B2-7 decisions" above where the implementation needed a choice; they do not
change it. Code: `mfa` (policy, TOTP, secret encryption, recovery codes, challenges, verifier, step-up state,
enrollment, self-service), `mfa.admin` (policy, selection, reset), `auth` (login MFA step, `MfaSignInService`,
`StepUpService`), `employee` (promotion), `organization` (onboarding completion), `profile` (`/me`).

**Endpoints and spelling**

- **B2-7/I1 — Endpoints as built** (spec §13.0, synchronized by the B2-7 docs PR): public `POST /auth/mfa/challenge`,
  `/auth/mfa/enroll`, `/auth/mfa/enroll/confirm`; authenticated `POST /me/mfa/enroll`, `/me/mfa/confirm`,
  `/me/step-up`, `/me/mfa/disable`, `/me/mfa/recovery-codes/regenerate`, `/me/mfa/reminder/dismiss`,
  `PUT /organization/security/mfa-policy`, `PUT /super-admin/employees/{id}/mfa-required`,
  `POST /admin/employees/{id}/mfa/reset`, `POST /super-admin/employees/{id}/promote-admin`,
  `POST /organization/onboarding/complete`. API paths spell **enroll** (never "enrol").
- **B2-7/I2 — No recovery-code acknowledgement.** The recovery codes are returned once by the call that enables MFA or
  regenerates them; there is no acknowledgement endpoint and no acknowledgement column. Any "I have saved them"
  confirmation is client-side (F1). (The spec's former §13.0 "recovery-code acknowledgement" was a leftover of the old
  mandatory-MFA gate; §15 item 16.)

**Voluntary enrollment (C3)**

- **B2-7/I3** — A wrong confirmation code from a signed-in session is a plain 400 and does not count toward the lockout
  (the caller already holds the secret). The authenticator label is the email plus the organization login key.
  Dismissing the reminder always records the latest time.

**Sign-in challenge (C4)**

- **B2-7/I4 — Answers.** A wrong code is a retryable 400 on `code` or `recoveryCode`; the fifth wrong code of a
  challenge, and any unusable challenge (unknown, expired, used, wrong purpose, invalidated, account locked, not active,
  MFA reset meanwhile), is one 401 "sign in again". A locked account's challenge is invalidated even with a right code.
- **B2-7/I5 — Records.** `login_attempt` records the password step as a success even when an MFA step follows.
  `LOGIN_SUCCEEDED` carries `mfaMethod` (`TOTP`, `RECOVERY_CODE`, `ENROLLED`) on MFA sign-ins. The failed sign-in count
  clears only when the MFA step completes.
- **B2-7/I6 — Challenges.** Lifetimes (5 and 10 minutes) and the limit of 5 are code constants. A new login does not end
  an older open challenge. Device label and IP are recorded, never compared. Challenges are ended with
  `invalidated_at`, never deleted. Deactivation does not touch open challenges (b2-6 unchanged): completion re-checks
  the account; disable and reset invalidate them eagerly.
- **B2-7/I7** — `POST /auth/login` returns `oneOf(TokenResponse, MfaChallengeResponse)`; the challenge answer has no
  `expiresIn`. An `ENROLL` challenge may still enroll while the policy offers enrollment; under `DISABLED` it ends.

**Step-up and self-service (C5)**

- **B2-7/I8 — Step-up.** Freshness is decided on the database clock (`verified_at > now() - 5 minutes`), for the calling
  session only. Once MFA is enabled a password-only step-up no longer counts. A step-up does not clear the failed
  sign-in count; `STEP_UP_VERIFIED` records the method and `sessionId`. For a caller without MFA a supplied code is
  ignored.
- **B2-7/I9 — V23 `employee.mfa_totp_pending_secret`, for safe re-enrollment.** `mfa_totp_secret` holds the active
  secret while enrolled, so a re-enrollment's new secret waits in the pending column (same AES-GCM format and account
  binding, CHECK: only while `mfa_enabled`). The existing secret and recovery codes keep working until the new
  authenticator is confirmed; restarting replaces only the pending secret; abandoning or failing changes nothing.
  Confirming atomically promotes and clears it, renews `mfa_enrolled_at`, sets the replay step to the later of the old
  step and the new code's, invalidates unused recovery codes and issues ten new ones (`MFA_ENROLLED`,
  `reenrolled=true`). Confirming needs no second step-up. Re-enrollment is refused under `DISABLED`.
- **B2-7/I10 — Order.** For disable and regenerate the state checks (409) come before the step-up check (403). Disable
  also invalidates open sign-in challenges; the caller's sessions stay.

**Administration (C6)**

- **B2-7/I11 — Check order** follows b2-6 deactivation as implemented: the caller's role (403, before any lookup), the
  target inside the caller's organization (404, same for unknown and foreign ids), who may act on whom (403), step-up,
  then the target's state (409).
- **B2-7/I12 — `MFA_SELECTION_CHANGED`** (not in the B2-7/25 list, added because the selection is a security setting):
  written by `PUT /super-admin/employees/{id}/mfa-required` with the before/after change of `mfaRequired`,
  `newlyRequired` and `revokedSessions`. Selection is allowed on oneself and on any status. Setting the current policy or
  selection again is a silent 204 with no audit row.
- **B2-7/I13 — Reset** exactly per B2-7/12 (owner re-confirmed 2026-09-26): an Admin resets Employees; a Super Admin
  Admins and Employees; nobody a Super Admin's or their own. A target without enabled MFA is 409. Reset clears the
  active and pending secrets, invalidates recovery codes and open challenges, and ends every session (`MFA_RESET`).
- **B2-7/I14 — Locking.** A policy change locks the organization row, then every unenrolled employee of the organization
  in id order, before reading role and selection, so a racing login or refresh cannot leave a session behind.
  `revokedSessions` counts revoked refresh tokens (the b2-6 convention).

**Onboarding and the Super Admin warning (C7)**

- **B2-7/I15 — `POST /organization/onboarding/complete`**: Super Admin only (403), sets `onboarding_completed_at` once at
  server time, audits `ORGANIZATION_ONBOARDING_COMPLETED` once, idempotent (204), no step-up.
- **B2-7/I16 — `needsAdditionalSuperAdmin`** (B2-7/13): a top-level boolean in `GET /me`, true only for a Super Admin
  whose organization has fewer than two `ACTIVE` Super Admins (counted in the caller's organization, from the token).
  Informational only: never exposes the count, blocks neither onboarding completion, sign-in nor workspace access, and
  reading it is not audited.
- **B2-7/I17 — Secret scanning.** `TotpTest` carries the RFC 6238 test secret; `.gitleaksignore` holds its exact
  fingerprint (commit `fee980f`), which must be updated if that commit is ever rewritten.

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
  the production hosting decision (§19) is made; it is also recorded in §12. **Done for the development stack in
  `b0-7`:** the backend service in `docker-compose.yml` has `stop_grace_period: 90s` (a literal), and `docker/smoke.sh`
  fails if it is below 90s or below 2 x the lifecycle timeout + 5s + a 20s margin (B0-7/8).
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
  `ScheduledJobInstancesTest` changed). **Residual risk, tracked for the hosting decision (§19):** Flyway runs in the app
  process, so the app's environment holds the owner credentials; the stronger setup is a separate migration job with
  `SPRING_FLYWAY_ENABLED=false` on the app. The `b0-7` development stack deliberately keeps in-app Flyway (B0-7/9). The V3 fail-fast check is approved: a missing runtime role fails the
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

### B0-7 decisions (owner-approved 2026-09-21; recorded here so they survive a session or repo reset)

Cite as "B0-7/3" and so on. Files: `Dockerfile`, `.dockerignore`, `docker-compose.yml`, `docker/postgres/init/01-roles.sh`,
`docker/smoke.sh`, CI job `compose-smoke`. All verified with `docker/smoke.sh` (71 checks) and mutation-tested (see
B0-7/12).

- **B0-7/1 — Compose location: Option C.** `docker-compose.yml` in this repository is the **backend development
  compose** (backend, PostgreSQL, Redis, Mailpit). **No frontend service**: the frontend does not exist until F0. The
  **complete platform compose moves to infrastructure ownership (`peoplehub-infra`) when the frontend exists.** The
  compose project is named `peoplehub-backend-dev` so it can never collide with a future platform project
  (`peoplehub`). This resolves §15 item 1. Spec §16.4 and §17 still describe the other split; correcting that text is a
  spec PR the owner decides on (this file does not edit the spec).
- **B0-7/2 — Dockerfile inside `b0-7`** (supersedes the earlier "separate branch" follow-up, §15 item 4).
- **B0-7/3 — Services: `db` (PostgreSQL 17, same tag as the tests), `redis` (7), `backend`, `mailpit`.** Mailpit, not
  Mailhog (unmaintained). Nothing sends mail until B1; the backend will reach it as `mailpit:1025`.
- **B0-7/4 — Base images: `eclipse-temurin:21-jdk-noble` (build) and `21-jre-noble` (runtime), glibc, chosen over
  Alpine for reliability, not size** (measured: TLS and the hardened prototype were equal on both; the deciding factors
  were later native-code libraries such as password hashing, and Apache POI fonts). All images are pinned by digest.
  **There is no Dependabot configuration in the repository, so nothing updates those digests: bump them on purpose**
  (adding Dependabot for the `docker`, `github-actions` and `maven` ecosystems is a tracked follow-up, not part of
  `b0-7`).
- **B0-7/5 — `ENV TZ=UTC` in the runtime image** (closes the B0-4 log-timestamp item, see §2).
- **B0-7/6 — Hardening on every container: `read_only`, `cap_drop: [ALL]`, `no-new-privileges`, a non-root user, and
  memory/CPU/pids limits.** Writable exceptions, all small `tmpfs` mounts: `/tmp` on all four services (the JVM and
  Tomcat, PostgreSQL, Redis and Mailpit's message store need scratch space; `noexec` works) and `/var/run/postgresql`
  for the PostgreSQL socket. PostgreSQL and Redis run as their images' own users (`70:70` on Alpine, `999:1000`), so no
  capability is needed. Approved limits (development defaults): backend 768m / 1.5 CPU / 300 pids, db 512m / 1 CPU,
  redis 256m / 0.5 CPU, mailpit 128m / 0.25 CPU.
- **B0-7/7 — Redis: AOF persistence on a named volume, a required password, no `maxmemory` and no eviction** (evicting a
  refresh-token family or a rate-limit bucket would be a bug; the container limit is the guard).
- **B0-7/8 — Backend `stop_grace_period: 90s`, a literal** (not a variable, so it cannot be set too low by accident).
  `docker/smoke.sh` fails if it is below 90s or below 2 x `SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE` + 5s + a 20s
  margin. Measured: `docker compose stop backend` logs "Graceful shutdown complete" and the JVM exits 143 (normal).
  Re-check at the production hosting decision (§19).
- **B0-7/9 — Role bootstrap.** `docker/postgres/init/01-roles.sh` runs once, on an empty data directory, as the
  bootstrap superuser, which exists only inside the `db` container (default name `pgbootstrap`: **PostgreSQL rejects a
  superuser or role named `pg_…`**). It creates the owner and runtime roles (names validated against
  `[a-z_][a-z0-9_]{0,62}`, not `pg_`-prefixed, and different), makes the owner the **database owner** (so it can run the
  migrations and create the trusted `btree_gist` extension without being a superuser), and grants the runtime role
  `CONNECT` and schema `USAGE`. Names and passwords reach SQL only as psql variables (`:"ident"`, `:'literal'`). It does
  **not** run again on an existing volume; change a role later with `ALTER ROLE` or `down -v`. In-app Flyway with the owner
  credentials in the backend's environment is kept for development (B0-6/3 residual risk).
- **B0-7/10 — Environment and secrets.** `.env` is git-ignored; `.env.example` holds names only. The four secrets
  (`POSTGRES_BOOTSTRAP_PASSWORD`, `PEOPLEHUB_DB_OWNER_PASSWORD`, `PEOPLEHUB_DB_RUNTIME_PASSWORD`, `REDIS_PASSWORD`) have
  **no defaults** and use `${VAR:?}`, so a missing one stops Compose before anything starts; `.env.example` ships them
  blank. Only explicitly listed variables are forwarded to the backend (an empty forwarded value would override the
  app's default with a blank). `SPRING_PROFILES_ACTIVE` is never set (the `local` profile must not run in a container).
  Environment variables are visible in `docker inspect`: acceptable for development only.
- **B0-7/11 — Ports, network, volumes.** Only the backend (`127.0.0.1:8080`) and Mailpit's UI (`127.0.0.1:18025`) are
  published, on loopback; ports are configurable with `PEOPLEHUB_BACKEND_PORT` and `PEOPLEHUB_MAILPIT_UI_PORT`. **The
  owner's machine already uses 5432, 1025 and 8025 for unrelated containers (`assessment-platform-*`): never stop or
  modify them, and never publish those ports.** PostgreSQL and Redis are not published (use a git-ignored
  `docker-compose.override.yml`). One project-scoped network; named volumes `pgdata` and `redisdata` (no bind mounts).
  Mailpit keeps no volume by design.
- **B0-7/12 — CI smoke, not required.** CI job `compose-smoke` runs `docker/smoke.sh` (bash; Git Bash locally). It is
  **not** a required check yet (a GitHub setting the owner applies). The script is hermetic (own project `peoplehub-smoke`,
  own ports, generated throwaway secrets, torn down even on failure) and checks the image, health and readiness, the
  RFC 9457 shape, Flyway-as-owner vs application-as-runtime-role, that the runtime role cannot UPDATE/DELETE/TRUNCATE the
  audit log and the owner is stopped by the trigger, hardening/limits/ports on all four services, restart on an existing
  volume (the role script must not run again) and graceful shutdown. It was mutation-tested: breaking the grace period,
  Redis capabilities, a published DB port and the application's role produced exactly those four failures. The script
  also fails in CI if `01-roles.sh` is committed without the executable bit.
- **B0-7/13 — Other approved choices.** Fat jar (layered extraction is a later optimisation). Bash smoke script.
  `restart: "no"` (a crash-looping dev backend should be seen). Applied without an explicit answer, following the plan: no
  Dependabot in `b0-7`, blank secrets in `.env.example`, and the timing of the spec-text PR stays open.
- **Not proven by `b0-7` (do not claim otherwise):** shutdown *under load* (in-flight request draining is proven by
  `GracefulShutdownTest`, b0-4; the jar has no slow endpoint), and the B0 exit criterion "a deliberately bad request
  returns a field-level RFC 7807 error", which the running stack cannot show without a write endpoint (proven by
  `@ApiWebTest` tests until B2). The CI job can only be confirmed green after a push.

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
- **MFA (b2-7):** `mfa` holds the policy model, TOTP, secret encryption, recovery codes, sign-in challenges, the
  second-factor verifier, step-up state and self-service; `mfa.admin` holds the organization policy, selection and
  reset; `auth` owns the login MFA step and `POST /me/step-up`. A future step-up protected action (spec §8.3) calls
  `StepUps.requireFresh`; a future flow that ends a person's MFA calls `MfaChallenges.invalidateOpen` and the
  recovery-code store. Secrets, codes and tokens never appear in a log, an audit row or a `toString()`.
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
- MFA (TOTP authenticator apps, recovery codes) is an **organization-configurable policy, disabled by default**
  (MFA/1–MFA/7 and "B2-7 decisions" in §2; spec §8.3 matches since PR #35). It is never a registration step and never
  globally mandatory. Anyone enrolled is always challenged at sign-in; when the organization's policy requires MFA for a
  user who has not enrolled, they get no session until they enroll; otherwise reminders encourage it but never block.
  Built in `b2-7` (PR #36).
  **Step-up auth** for the actions listed in spec §8.3: fresh (5 minutes), bound to the calling session, password plus
  a TOTP or recovery code once MFA is enabled, the password alone otherwise, enrollment first when the policy requires
  MFA (B2-7/15). A new step-up protected action calls `StepUps.requireFresh(principal)` in its service; never invent
  another mechanism.
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
- **As built in `b0-7`** (decisions B0-7/1–B0-7/13 in §2): the `Dockerfile` (multi-stage, Temurin 21 noble, non-root
  uid 10001, `TZ=UTC`, liveness `HEALTHCHECK`, base images pinned by digest) and the **backend development
  `docker-compose.yml`** (backend, PostgreSQL, Redis, Mailpit; hardened, limited, loopback ports, 90s stop grace). Verify
  any change to either with `bash docker/smoke.sh`. Keep the compose image tags equal to `TestcontainersConfiguration`
  (the smoke script checks it). Never publish host ports 5432, 1025 or 8025, and never touch the owner's unrelated
  containers (§14).
- **Docker Compose location: resolved by the owner (Option C, B0-7/1).** This repository holds the backend
  development compose; the complete platform compose (with the frontend) moves to infrastructure ownership
  (`peoplehub-infra`) when the frontend exists. Do not add a frontend service here.

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
- **B0 progress:** B0 Foundation complete: b0-1 through b0-7 merged and verified (b0-7 via PR #9). v9 (§16.5)
  says to continue from this actual state and not to rebuild merged work. The B0 exit criterion "audit table rejects
  UPDATE/DELETE" is demonstrated by `AuditLogMigrationTest.auditTableRejectsUpdateAndDelete` (and again, through the
  running stack, by `docker/smoke.sh`); "`docker compose up` boots the stack" by `docker/smoke.sh`. **Still needing final
  B0 verification after `b0-7`:** the field-level RFC 7807 error (test-level only until B2), "OpenAPI published" (the
  snapshot and breaking-change check is a queued follow-up), and the other queued items before B0 closes (migration-edit
  CI guard, SAST, dependency scan, SBOM, gitleaks pre-commit hook, Dependabot).
- **B1 branches:** `b1-1-email-service-outbox` · `b1-2-templates-retry-log` · `b1-3-inapp-sse` ·
  `b1-4-bounce-suppression` (each prefixed with a `<type>/`; the merged branches used slightly
  adapted slugs -- `feature/b1-1-email-service-outbox`, `feature/b1-2-templates-retry-email-sending`,
  `feature/b1-3-inapp-sse`, `feature/b1-4-bounce-suppression` -- see "B1 progress" below for what each
  one actually contains).
- **B1 exit criteria (§17):** invite mail sent through dev SMTP with retry; failure visible to Admin;
  SSE test passes.
- **B1 progress:** **b1-1 is merged** (`feature/b1-1-email-service-outbox`: V4 `email_outbox`
  migration, `EmailOutboxWriter`, `EmailMessage`/`EmailPayload`; PR #11). **b1-2 is merged**
  (`feature/b1-2-templates-retry-email-sending`: V5 migration, `EmailOutboxProcessor`,
  `SmtpEmailSender`, classpath templates, retry/backoff, transient/permanent failure classification
  with no provider-specific rules, plus a follow-up fix disabling the auto-registered SMTP health
  indicator that was breaking CI's root `/actuator/health` check; PR #12). **b1-3 is merged**
  (`feature/b1-3-inapp-sse`: V6 `notification`/`notification_preference` migration, Redis pub/sub
  cross-instance SSE fan-out (not an in-memory `SseEmitter` registry alone), the initial critical
  notification-type list (`PASSWORD_RESET`, `NEW_DEVICE_PAIRED`, `EMPLOYEE_INVITED`), a
  `notification-test`-profile-guarded test-only controller (no production notification endpoints yet
  -- those wait for B2/B3, once a real authenticated principal exists), plus a follow-up fix for a
  `docker/smoke.sh` timestamp-extraction portability bug (an external `sed` dialect difference
  between a developer machine and the CI runner, replaced with bash's own `[[ =~ ]]`); PR #13).
  **b1-4 is merged** (`feature/b1-4-bounce-suppression`: V7 `email_suppression` migration, global by
  email address with no `organization_id` (a deliberate departure from V4/V6's forward-compatible
  per-tenant pattern -- a bounced/complained-about address is bad for every tenant), a real,
  always-active `POST /webhooks/email/events` webhook (unlike b1-1/b1-2/b1-3's deferred/test-only
  endpoints) authenticated by JDK-native HMAC-SHA256 (no provider SDK), a surgical
  suppression-check addition to `EmailOutboxProcessor` (only `ADDRESS_REJECTED` permanent failures
  suppress; transient failures and other permanent reasons such as `AUTHENTICATION_FAILED` never
  do), and `EmailSuppressionNotifier` built on the b1-3 `NotificationWriter` pipeline but not yet
  wired to a real caller (no Admin/employee identity exists until B2/B3); PR #15). **B1 (Email &
  notification platform) complete: b1-1, b1-2, b1-3 and b1-4 merged and verified.** Update this line
  when a phase merges.
- **B2 progress:** **b2-1 is merged** (`feature/b2-1-org-tenant-employee-schema`: V8 `organization`, V9 `employee`,
  V10 `employee_invitation`, V11 `refresh_token`/`login_attempt`/`mfa_recovery_code`, V12 tenant FK retrofit; PR #17).
  **b2-2 is merged** (`feature/b2-2-org-bootstrap-founder-verification`: V13 `organization_verification_token`,
  registration, founder email verification and resend through the outbox, Argon2id password hashing; PR #20; decisions
  B2-2/1-B2-2/8). **b2-3 is merged** (`feature/b2-3-login-jwt-refresh-tenant-context`: Spring Security deny-by-default
  chain, ES256 JWT access tokens, V14 refresh-token rotation with reuse detection, logout, per-request tenant context,
  `GET /me`, authentication audit events; PR #23; decisions B2-3/1-B2-3/21). **b2-4 is merged**
  (`feature/b2-4-invite-activation-admin-invite`: V15 `employee_invitation` hardening, Employee and direct Admin
  invitations through the outbox, public preview and acceptance with the invitee's own password, resend and revoke,
  invitation audit events, `firstName`/`welcomeSeenAt` on `/me` and `POST /me/welcome/ack`; PR #26; decisions
  B2-4/O1-B2-4/O15). **b2-5 is merged** (`feature/b2-5-password-policy-lockout-reset`: V16 `employee` lockout
  columns (`failed_login_count`, `locked_until`) and the `PASSWORD_RESET`/`PASSWORD_CHANGED` refresh-token revoke
  reasons, V17 `password_reset_token`; the password policy (12-128 characters, no composition rules) with an offline
  breached-password list; per-account lockout with backoff (5 failures, 1 minute doubling to 30); forgot/reset password
  (`POST /auth/forgot-password`, `POST /auth/reset-password`: non-enumerating, single-use hashed code, throttled, ends
  every session and clears the lockout, Origin-checked); change password (`POST /me/password`: current password
  required, other sessions ended); audit events `ACCOUNT_LOCKED`, `PASSWORD_RESET_REQUESTED`,
  `PASSWORD_RESET_COMPLETED` and `PASSWORD_CHANGED`; PR #29; decisions B2-5/P1-B2-5/P13). **b2-6 is merged**
  (`feature/b2-6-sessions-deactivation-revoke`: V18 adds the `SESSION_REVOKED` and `DEACTIVATED` refresh-token revoke
  reasons (no new column or grant); `GET /me/sessions` (paginated, the caller's own active sessions, no token, hash or
  IP), `DELETE /me/sessions/{sessionId}` and `POST /me/sessions/revoke-others`; coarse "browser on OS" device labels
  (at most 64 characters, never the raw User-Agent or the IP); `POST /admin/employees/{id}/deactivate` (optional
  `exitDate`) and `/reactivate`, with checks in the order 404, 403, 409, 400, sessions revoked, an invitee's open
  invitation revoked and unused reset codes invalidated; login and refresh re-check the employee under a row lock so a
  concurrent deactivation never leaves a usable session; `REFRESH_TOKEN_REUSE_DETECTED` raised only for `ROTATED` and
  `LOGOUT` tokens; audit events `SESSION_REVOKED`, `OTHER_SESSIONS_REVOKED`, `EMPLOYEE_DEACTIVATED` and
  `EMPLOYEE_REACTIVATED`; race, tenant-isolation and runtime-role tests and `docker/smoke.sh` checks; a test-only fix
  keeping the scheduled outbox job out of `EmailOutboxProcessorTest`; PR #32; decisions B2-6/1-B2-6/16). **b2-7 is
  merged** (spec correction `docs/b2-7-spec-mfa-policy-correction`, PR #35; then `feature/b2-7-mfa-stepup-onboarding`,
  PR #36, checkpoints C1-C7: V19 organization MFA policy and employee MFA state, V20 recovery-code tenant binding and
  invalidation, V21 `mfa_challenge`, V22 `session_step_up`, V23 the re-enrollment pending secret; TOTP (JDK HMAC) and
  AES-256-GCM secret encryption with a required environment key; voluntary enrollment and `/me` MFA state and reminder;
  the sign-in `CHALLENGE`/`ENROLL` step with replay protection, single-use recovery codes and lockout counting;
  session-bound step-up; re-enrollment, disable and recovery-code regeneration; the policy change, selection, MFA reset
  and Employee → Admin promotion; founder onboarding completion and `needsAdditionalSuperAdmin`; end-to-end, tenant,
  concurrency and no-leak tests; decisions B2-7/1-B2-7/27 and B2-7/I1-B2-7/I17). **Next: b2-8** (RLS and the
  cross-tenant security suite, B2-3/20). Update this line when a phase merges.

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
  the compose file lives, so it does not resolve #1. **Since resolved by the owner (B0-7/1, Option C):** #1 and #4 are
  settled as recorded in the table below; #5 stands (CI does not use compose for Testcontainers).
- **2** (`approver_id` vs `resolved_approver_id`): unchanged (§12 vs §7.5). Still ask before B7.
- **3** (§2 cross-refs to 13.1/13.2): unchanged in v9's §2 table. Still cosmetic; use §13.2 / §13.3.
- **6, 7, 8** (page-size cap, RFC 7807 wording and `your-domain`, `b0-4` naming): unchanged; the recorded `b0-3` owner
  decisions still stand.
- **9** (`/actuator/health` as liveness): unchanged in v9 §16.4 and §22. The `b0-4` owner-approved deviation stands (the
  spec text is still to be corrected).

Rows 10–13 are new inconsistencies **inside v9 itself** (or between v9 and the repository), found while reconciling.

| # | Issue | Resolve before | Working assumption until resolved |
|---|-------|----------------|-----------------------------------|
| 1 | `docker-compose.yml` location: §16.4 says `peoplehub-infra`; §17 B0 says this repo (`b0-7`) and includes a frontend container that doesn't exist until F0 | `b0-7` | **Resolved by the owner (B0-7/1, Option C):** this repo holds the *backend development* compose (backend, PostgreSQL, Redis, Mailpit; no frontend); the *complete platform* compose moves to `peoplehub-infra` when the frontend exists. The spec text (§16.4, §17) still describes the other split; aligning it is a spec PR for the owner to decide. |
| 2 | `approval_request` column is `approver_id` in §12 but `resolved_approver_id` in §7.5 | B7 | Ask before creating the table. |
| 3 | §2 cross-refs are stale: validation is §13.2 (cited as 13.1), caching is §13.3 (cited as 13.2) | next spec PR | Cosmetic; use §13.2 / §13.3. |
| 4 | §17 B0 scope items with no branch: Redis, injectable clock, Dockerfile (16.4 puts the Dockerfile in this repo) | assigned per branch plan | Clock → `b0-1` (done); Redis → `b0-2` (done); Dockerfile → built inside `b0-7` (owner decision B0-7/2, superseding "separate branch"). |
| 5 | §16.4 says the compose file is what CI uses for Testcontainers; Testcontainers manages its own containers and does not consume compose | `b0-2` | Testcontainers is self-contained; CI does not depend on compose for tests. (`b0-7` adds a *separate* `compose-smoke` job that boots the development stack; it is not the integration-test setup and is not a required check.) |
| 6 | §13.1 gives `size` a max of 100 but does not say what happens above it | decided in `b0-3` (owner approved) | `size` > 100 is a 400 (`invalid-page-request`) that points to the export; no silent clamp. |
| 7 | §13 says "RFC 7807"; RFC 9457 obsoletes it with the same shape. §9.2 still has the `your-domain` placeholder, so no real base URL exists for problem `type` | decided in `b0-3` (owner approved) | Cite RFC 9457; `type` and `instance` are URNs (`urn:peoplehub:problem:*`, `urn:peoplehub:request:*`). |
| 8 | §17 names `b0-4` "logging-validation-observability", but the RFC 7807 error body needs a correlation id and the validation `@ControllerAdvice`, both required by `b0-3`'s pagination errors | decided in `b0-3` (owner approved) | Correlation-id filter and `GlobalExceptionHandler` live in `b0-3`; `b0-4` adds JSON logging, actor id, request log, Sentry, actuator on top. |
| 9 | §16.4 names `/actuator/health` as the liveness endpoint, but Spring Boot's aggregate `/actuator/health` includes the database and Redis, so using it for restarts would let a DB/Redis blip restart the container | decided in `b0-4` (owner approved, D1) | Liveness is `/actuator/health/liveness`, readiness (adds DB + Redis) is `/actuator/health/readiness`; the Dockerfile health check uses liveness. Spec text to be corrected in the next spec PR. |
| 10 | v9's header checkpoint and §16.5 table are **stale for the repository**: they say `b0-5` is "implemented locally, **not yet committed**" and the `b0-4` docs PR is "awaiting manual merge". Both are done (b0-4 docs PR #5, b0-5 PR #6). They also describe the 5s as a "second shutdown wait", whereas the measured behaviour is an unwind allowance for an interrupted job (`await-termination: false`; see "B0-5 decisions") | next spec PR | This file's status line and "B0-5 decisions" describe the real state; do not "re-do" b0-5 because of v9's checkpoint wording. |
| 11 | v9 §13.3 (last bullet) still says a multi-tenant future is "currently out of scope, Section 1", which contradicts D21, §1 and §2.1 (multi-organization is in scope) | next spec PR | Multi-organization is in scope (D21–D30); cache keys are organization-scoped. |
| 12 | Tenant key naming: §12's base tables (`department`, `employee`, `calendar_event`, `month_lock`, `leave_type`) write `org_id`, while §2.1.1/§12.1 use `organization_id` and §13.3 writes `orgId` | before `b2-1` | Treat all as the same tenant key; **confirm the exact column name with the owner before creating the tenant schema.** `audit_log` (B0-6/1) already uses `organization_id`; if the owner picks `org_id` for the other tables, that table needs a forward migration to match. |
| 13 | v9 §16.5 lets `b0-6` add `organization_id` "forward-compatibly", and §12.1 says audit rows carry it, but no `organization` table exists until B2 | before `b0-6` | **B0-6 design resolved; B2 completion pending.** B0-6/1: `audit_log.organization_id UUID NOT NULL`, no FK, no fabricated value ever. B2 still owes: the `organization` table, a matching primary-key type (UUID), the `audit_log` FK, tenant RLS or an equivalent DB defence in depth, and cross-tenant integration tests. Do not implement B2 early. |
| 14 | §4.9 says comp-off credit is issued **automatically** "when the day is finalized by the nightly job"; this conflicted with a new owner requirement that weekend/holiday work instead create a pending comp-off request needing Admin/Manager approval. Recorded in "Configurable leave, comp-off & weekend policy" (§2) | before `b10-1-credit-on-finalize` | **Owner-approved implementation decision, 2026-09-23: approval-gated, not automatic** (recorded in §2, not a spec edit). `PROJECT_MASTER_SPEC.md` §4.9 still reads "credit is issued when the day is finalized" and **must be corrected through a future `docs:` spec PR before comp-off implementation begins** (§16.2). Until that PR lands, implementation planning for comp-off follows the approved decision recorded in §2. |
| 15 | Spec D6, §2.1.3 (step 6), §2.1.5, §3.3, §8.2, §8.3, §15 item 5 and §22.1 made MFA **mandatory** for Admin and Super Admin (the founder before first workspace access, a directly invited Admin before first Admin access, a promoted Admin at next login). This conflicted with the owner's decision that MFA is an organization-configurable policy, **disabled by default**. Recorded in "MFA policy decisions" (§2) | before `b2-7-mfa-stepup-onboarding` | **Resolved.** The spec was corrected to the policy model (configurable, default `DISABLED`, MFA/1–MFA/7) by PR #35 before `b2-7` began, including §15 item 5 and the §22.1 launch gates; `b2-7` implemented it (PR #36). Do not reintroduce globally mandatory MFA wording. |
| 16 | After PR #35, spec §13.0 still listed a "recovery-code acknowledgement" (a leftover of the old mandatory-MFA gate), named onboarding completion `PATCH /api/v1/organization/onboarding`, and lacked the MFA endpoints `b2-7` built; §12 lacked the `b2-7` MFA columns and tables | the B2-7 docs PR | **Resolved by `docs/b2-7-status-spec-sync`:** §13.0 lists the built MFA, step-up and onboarding endpoints (`POST /api/v1/organization/onboarding/complete`) and states there is no acknowledgement endpoint or state (B2-7/I2); §8.3 describes sign-in, recovery codes, re-enrollment (V23), reset, step-up and `needsAdditionalSuperAdmin`; §12/§12.1 list `organization.mfa_policy`, the employee MFA columns including `mfa_totp_pending_secret`, `mfa_challenge`, `session_step_up` and `mfa_recovery_code.organization_id`/`invalidated_at`; "re-enrolment" is spelled "re-enrollment". |

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
