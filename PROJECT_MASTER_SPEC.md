# PeopleHub — Master Build Document (v9)

**Status:** Active implementation specification. Supersedes v8. This is the single source of truth from the current B0 implementation state onward. Any scope change updates this document first through a branch + pull request (Section 16).

**Current implementation checkpoint (do not reset/rebuild completed work):**
- `b0-1`, `b0-2`, `b0-3`: merged baseline.
- `b0-4` logging / Sentry / health / shutdown: implementation merged as PR #4; the separate `docs/claude-md-b0-4-merged` documentation PR is still awaiting manual GitHub merge at the time of this revision.
- `b0-5` ShedLock scheduler: implemented and verified locally but **not yet committed** at this checkpoint. Before commit, apply/review the requested second-shutdown-wait change to 5 seconds, document the combined worst-case shutdown time, and carry the orchestrator shutdown-budget follow-up into `b0-7`.
- `b0-6` append-only audit log: not started.
- `b0-7` docker-compose: not started.
- Existing B0 work is preserved. The organization-bootstrap / tenant-isolation changes introduced by v9 are implemented in the appropriate future branches (primarily B2/F1 and security tests), not by rewriting already-merged B0 branches unless a genuine B0 prerequisite is discovered.

**What v9 adds (on top of v8)**
1. Adds the missing **public organization registration/bootstrap flow**. A founder creates an organization and an individual account; that account becomes the organization's first `SUPER_ADMIN`.
2. Changes the deployment model from “one deployed instance = one company” to a **multi-organization SaaS application with hard tenant isolation**. Every organization can see and operate only on its own tenant. No tenant may list, search, infer, count, inspect, reference, or access another tenant or its users/data.
3. Makes organization identity explicit: each organization has an immutable internal `organization_id` and a unique, normalized public **organization login key**. The UI labels this field “Organization” / “Organization name”; uniqueness conflicts and login failures use non-enumerating responses.
4. Defines the complete identity lifecycle: founder registration → email verification → first-time organization setup (including the organization's MFA policy) → real Super Admin dashboard → invite Admins/employees → invite acceptance → private password creation → role-aware dashboard.
5. Adds **direct Admin invitation** while preserving the invariant that every Admin/Super Admin is also an Employee record. Super Admin may still promote an existing employee.
6. Makes deactivation semantics explicit: deactivated users immediately lose login/API/session access while historical attendance, leave, approvals and audit records remain. Hard deletion is not the normal employee-exit operation.
7. Declares the supplied Landing, Registration, Login and Dashboard HTML prototypes to be **visual acceptance references only**. Production frontend remains Next.js App Router + TypeScript + Tailwind + TanStack Query; HTML/CSS/JS prototypes are not copied in as the runtime implementation.
8. Freezes the premium PeopleHub visual language: warm ivory, deep navy, royal blue, sky, coral/peach, lime and gold; glass surfaces, layered ambient shadows, serif display headings paired with geometric sans UI text, tabular/monospaced counters, gradient charts, live pulse indicators and restrained motion.
9. Adds the missing auth/onboarding routes, APIs, schema fields, tenant-boundary tests, email templates, frontend phases and production-readiness gates required to make the above flow implementable end-to-end.
10. Records the current B0 implementation checkpoint so implementation continues from the actual repository state rather than restarting from the document.

**What v8 does (on top of v7)**
1. Makes the **role dashboards explicit** as their own spec (new Section 5.6): the live running-timer widget is **Employee-only** — Admin and Super Admin never see a personal timer on their console home, even though every Admin/Super Admin is also an Employee record (3.2) and still clocks in/out on their own "My Attendance" screen (5.1). Their dashboard instead leads with an **org-wide hours widget** (D18).
2. Adds a **pending-approvals banner** on the Admin/Super Admin dashboard: a clickable tagline reading "`{n}` requests need your approval," which opens the Approvals queue directly, and disappears/recomputes the moment nothing is left pending (D19, 5.6, 7.5).
3. Gives the employee dashboard its own smaller version of the same pattern — a "`{n}` of your requests are pending" line linking to "My requests" (5.6).
4. Turns the Employee Directory's search (3.5) and the Live Attendance Dashboard's search (5.5) into one fully specified behavior: typing a name resolves to that employee's full detail card; picking/searching a department filters the list down to that department's roster (D20, 3.5).
5. Closes gap #48–#50 found in this pass (register in Section 20).

**What v7 did**
1. Rebuilds attendance around the rule: *once clocked in, the clock keeps running across tabs, closed browsers, sleep and network loss; it stops only on manual check-out or a real computer shutdown, and every non-manual stop is clearly labelled.*
2. Adds employee self-service reports: daily, weekly, monthly, plus any previous month.
3. Closes 40 gaps found in the v3 audit (register in Section 20).
4. Reorders the build: **Backend (complete) → Frontend → Desktop agent → Release**, every change on its own short-lived branch, never directly on `main`.
5. Turns departments into a real access-control boundary: Admin creates/manages departments, assigns employees to them at creation or afterwards, and department-mates can see **only each other's names** (Section 3.1, D11).
6. Splits the holiday calendar into a full org calendar of Admin-managed dated entries (holidays *and* events), read-only for employees (Section 6.6, D12).
7. Adds a Live Attendance Dashboard for Admin: real-time buckets for clocked-in-active, clocked-in-idle, not-yet-clocked-in, and clocked-out (Section 5.5, D13).
8. Makes the admin employee list a first-class, explicit feature — searchable, filterable, paginated, sortable — instead of an implied side-effect of "employees CRUD" (Section 3.5).
9. Turns pagination and sorting into a hard API standard applied consistently to every list endpoint (Section 13, D14).
10. Specifies request validation, structured logging, and an explicit caching strategy as concrete, buildable pieces (Sections 13.1, 13.2, 14, D15).
11. Adds containerization and a documented local/prod environment (Dockerfile, docker-compose, `.env` handling) (Section 16.4, D16).
12. Maps your installed IntelliJ plugins to concrete build-phase usage (Section 21).
13. Adds a consolidated Production Readiness checklist (Section 22) gating go-live.
14. **Adds a one-time post-activation welcome screen and a persistent "Hello, `{firstName}`" greeting across the app**, with `firstName` derived once server-side from the employee's stored name and exposed via a new `GET /me` endpoint — no page re-implements name parsing (Section 10.2–10.3, D17, new gap #47).

**Placeholder name:** "PeopleHub" lives in one config value (`APP_NAME`), never hardcoded in UI strings.

**Conventions:** `[confirm]` marks a default I chose that you should confirm. Exact leave-policy numbers (accrual amounts, carry-forward, yearly extra holidays) lived in v2 and are kept data-driven here; carry them over into the `leave_type` seed data.

---

## 0. Decisions made in this version (change any you disagree with)

| # | Decision | Why |
|---|---|---|
| D1 | **Rest counts as work time.** Sleep, hibernate, lock screen, idle, screen off, lid closed, tab closed, browser closed, app logout, network loss: none of these stop the clock. | Your rule: only a real shutdown or manual check-out stops it. |
| D2 | **Shutdown detection requires a small desktop agent.** A website cannot detect that the computer is shutting down. Without the agent, the system cannot know. | Browser limitation, not a design choice. See 4.4. |
| D3 | **Restart and OS logoff are treated like shutdown** (the user session ends). The employee checks in again after boot; the gap is not counted. | Cannot reliably tell "restart" from "shutdown" across all OSes; consistent and simple. |
| D4 | **No midnight auto-checkout.** At org midnight a running session is *split* into two sessions and keeps running, so each day gets the right hours. A safety cap protects against forgotten check-outs: warn at 12h, auto-close at 20h continuous (both configurable, cap can be turned off). | Midnight auto-checkout contradicts your rule; unbounded sessions are a data-integrity risk. |
| D5 | Corrections are allowed up to **30 days back**; Admin can **lock a month** after that. | Without a window, history can be rewritten forever. |
| D6 | **MFA is an organization policy: recommended, not globally mandatory.** A Super Admin sets the organization's MFA policy (default `DISABLED`; also `OPTIONAL`, `REQUIRED_FOR_ADMINS`, `REQUIRED_FOR_SELECTED_USERS`, `REQUIRED_FOR_ALL`). Under a `REQUIRED_*` policy, only the users it covers must complete MFA enrollment before they receive a session. Under `OPTIONAL` (and for users a `REQUIRED_*` policy does not cover), MFA never blocks login and users may enable it voluntarily. Under `DISABLED`, MFA is not offered. See 8.3. | Organizations differ in security needs: strong MFA stays available and enforceable without being forced on every workspace (owner decision, 2026-09-23; replaces the earlier "mandatory for Admin and Super Admin" rule). |
| D7 | **Delete Admin = anonymise (tombstone).** PII and login are destroyed irreversibly; attendance, leave and audit rows stay so history and foreign keys stay intact. | v3's "delete" conflicted with audit-log integrity. |
| D8 | **Approval links in emails open a confirmation page**; nothing changes until a button is clicked. | Mail scanners auto-click links and would consume or trigger single-use tokens. |
| D9 | **Nobody approves or edits their own leave or attendance.** A fallback chain picks another approver. At least 2 Super Admins are recommended and the system warns if there is only one. | Prevents self-approval and stalled requests. |
| D10 | **Frontend and API share one registrable domain** (`app.example.com` and `api.example.com`). | Cross-site cookies break the refresh-token design (8.1). |
| D11 | **Department membership grants name-only visibility of department-mates, nothing more.** No employee ever sees another department's roster or anyone's attendance/leave/personal fields except their own. | You asked for peer visibility inside a team without turning it into a company directory or a privacy leak. |
| D12 | **The calendar is one org-wide list of dated entries** (`HOLIDAY` and `EVENT`), Admin-authored, read-only for employees. Only `HOLIDAY` entries feed attendance/leave-day math; `EVENT` entries are informational. | Keeps the existing holiday-driven attendance logic (4.3, 6.6) intact while giving Admin a general-purpose calendar without a second system. |
| D13 | **Presence is bucketed into four states, computed, not stored:** Clocked In & Active, Clocked In & Idle, Not Clocked In (yet), Clocked Out. Nothing here changes what stops the clock (still only D1/R3) — it's a read view over existing session + heartbeat data. | Admin needs one glance at "who's actually working right now" without inventing a new attendance state machine. |
| D14 | **Every list endpoint takes the same `page`/`size`/`sort` contract and returns the same envelope shape** (items, page, size, totalElements, totalPages). No endpoint invents its own pagination style. | One frontend table component and one API-client helper can drive every list screen (employees, departments, calendar, reports, notifications, audit log). |
| D15 | **Caching is cache-aside over Redis, keyed and short-TTL'd, with explicit eviction on every write — never used for anything that must be instantly consistent** (open sessions, leave balances mid-approval, the live dashboard). | Speeds up read-heavy, rarely-changing lookups (org settings, leave types, department list, calendar) without risking stale attendance/leave data. |
| D16 | **Local dev and CI run the same containers as production** (Postgres, Redis, backend, frontend), via one `docker-compose.yml`; the only difference between environments is which `.env` file is loaded. | Removes "works on my machine" drift and makes "production ready" a concrete, testable target rather than a hope. |
| D17 | **`firstName` is derived once, server-side, from the stored `name` field, and shown once as a welcome screen plus persistently as a shell greeting.** The frontend never parses names itself. | One source of truth for name-splitting (title/multiple given names/no-space names all handled in one place); a one-time welcome moment doesn't nag returning users. |
| D18 | **The live running-timer widget only ever renders on the Employee dashboard.** The Admin/Super Admin console home never shows a personal timer — it shows an **org-wide "hours" widget** instead (today's total/average hours across employees, drilling into 5.2's reports). An Admin/Super Admin still has their own timer on their personal **"My Attendance"** screen (5.1), same as any employee — this only affects what the *console home* leads with. | A running stopwatch on an admin's landing screen reads as "am I clocked in" noise on a screen whose job is "how is the org doing"; their own clock-in still exists, just one screen over. |
| D19 | **Both dashboards carry a pending-requests tagline, scoped to what that role can act on.** Admin/Super Admin see "`{n}` requests need your approval" (their own pending approval queue, 7.2); Employees see "`{n}` of your requests are pending" (their own submitted leave/corrections, 6.3/4.7). Clicking either navigates straight to that queue; approving/rejecting (or the request being decided by someone else) updates `n` live, and the tagline disappears entirely at `n = 0` rather than showing "0 requests." | Turns "you have pending work" into a single glanceable, actionable line instead of something Admin has to remember to go check for. |
| D20 | **Directory and Live Attendance search share one resolution rule:** a **name** query resolves to matching employee(s) and opens straight to full detail (3.5's profile view); a **department** selection (chip, dropdown, or typing a department name) filters the current list down to that department's roster only — never a fuzzy mix of the two in one box. | You asked for the searching mechanism to be spelled out explicitly rather than left implicit; one predictable rule beats two different screens behaving differently for a query that looks the same. |
| D21 | **PeopleHub is multi-organization with hard tenant isolation.** A shared deployment may host many organizations, but an authenticated principal is bound to exactly one `organization_id`. Every tenant-owned query, mutation, cache key, event, export, SSE stream, audit row and background job is scoped by that id. | Registration only makes sense as a public SaaS flow if multiple organizations can coexist safely. |
| D22 | **No tenant discovery or cross-tenant visibility exists.** An organization cannot list other organizations, know how many exist, search their names/users, access their IDs, or distinguish “wrong organization” from “unknown email/password” at login. | Prevents data leakage and organization/account enumeration. |
| D23 | **The first account created during organization registration is the founding Super Admin.** It is an individual employee identity using the founder's own company email and privately chosen password; shared “superadmin” credentials are forbidden. | Establishes an auditable owner and closes the bootstrap gap. |
| D24 | **Only organization founders self-register. Employees and additional Admins do not publicly sign up.** They enter through single-use, expiring email invitations and choose their own passwords. | Keeps membership controlled by the organization and passwords private. |
| D25 | **Super Admin can add an Admin in two ways:** promote an existing active Employee, or invite a new person directly as `ADMIN`. A directly invited Admin is still created as an Employee record with `role=ADMIN`, `status=INVITED`. | Preserves one identity model while supporting practical first-time setup. |
| D26 | **Deactivation revokes authority immediately but preserves history.** Deactivate blocks login, revokes refresh-token families/sessions and device authorization, removes active approval authority and preserves referential/audit/history rows. | Employee exit must not destroy payroll/attendance/audit history. |
| D27 | **Login for every role uses Organization + company email + password.** The user never selects a role. The server resolves the tenant, authenticates the account inside that tenant, then routes by stored role. | One predictable login for Employee/Admin/Super Admin. |
| D28 | **Supplied HTML pages are visual contracts, not implementation technology.** Production must reproduce their approved look/feel and responsive behavior using the v9 frontend stack. | Prevents prototype HTML from silently replacing the specified Next.js architecture. |
| D29 | **Registration is atomic and non-enumerating.** Organization + founder Employee/Auth identity are created in one transaction after validation; partial tenants are never left behind. Public responses do not reveal whether another organization/account exists beyond what is strictly required to complete registration safely. | Security and data-integrity requirement. |
| D30 | **Tenant isolation is enforced twice:** application-layer organization scoping is mandatory and PostgreSQL tenant protection/RLS (or an equivalently strong DB-level policy proven by tests) is defense-in-depth for tenant-owned tables. | A missed repository predicate must not become a cross-company breach. |

---

## 1. Product Summary

PeopleHub is a multi-role HR operations SaaS that can host **multiple organizations in one deployment while behaving as a completely private workspace to each organization**. A user enters their Organization + company email + password at login; after authentication, every request is bound to exactly one `organization_id`. An organization can operate only inside its own boundary and cannot discover, count, search, inspect or access any other organization or its users/data. It covers:

- **Attendance:** server-authoritative sessions, check-in/out, shutdown-aware closing, correction requests, month locking
- **Employee self-service reports:** daily, weekly, monthly and previous months, with downloads
- **Leave:** casual / sick / comp-off, ledger-based balances, approvals, holiday calendar
- **Org structure:** departments/teams with admin-managed membership and name-only peer visibility; roles Super Admin, Admin, Employee
- **Org calendar:** Admin-authored holidays and general events, date-picker driven, read-only for employees
- **Approvals:** one engine for leave and attendance corrections, via console or safe email link
- **Notifications:** reliable email (outbox, retry, bounce handling) plus real-time in-app
- **Admin reports** (CSV/Excel/PDF), audit log, Super Admin security dashboard, live attendance dashboard (who's in/idle/out)
- **Celebration layer** on clock-in and long work days
- **Resilient sessions:** no data loss and no forced interruption from tab closes, network drops or inactivity
- **Optional desktop agent** for accurate shutdown detection

Standalone project (not a NEXUS module): its own auth, org and data model.

---

## 2. Tech Stack

| Layer | Choice |
|---|---|
| Backend | Java 21, Spring Boot (modular monolith, package-by-feature) |
| Database | PostgreSQL (`timestamptz` everywhere; `btree_gist` for overlap constraints) |
| Migrations | Flyway, forward-only |
| Auth | JWT access token (15 min, in memory only) + rotating refresh token in httpOnly Secure SameSite cookie; signing keys with `kid` rotation |
| Cache / coordination | Redis — infra concerns (refresh-token families, rate limiting, presence, device liveness) **and** domain read-cache (Spring Cache abstraction, 13.2) for rarely-changing lookups |
| Validation | Jakarta Bean Validation (Hibernate Validator) on every request DTO; custom validators for cross-field/business rules (13.1) |
| Logging | Logback with a JSON encoder (structured, one line per event), MDC-injected correlation id + actor id on every log line, environment-driven log levels (14.1) |
| Scheduled jobs | Spring `@Scheduled` + **ShedLock** (no duplicate runs across instances) + catch-up on restart; all day-boundary logic uses the **org timezone** |
| Email | Provider-agnostic `EmailService` (Brevo/SES/SMTP), **transactional outbox**, retry with backoff, bounce/complaint webhooks |
| Real-time in-app | Server-Sent Events (SSE) with polling fallback |
| API contract | OpenAPI generated from the backend; the frontend client is generated from it; CI fails on breaking changes |
| Frontend | Next.js (App Router), TypeScript, Tailwind |
| Frontend data | TanStack Query (refetch on focus, poll while visible), BroadcastChannel for cross-tab sync |
| Desktop agent | Tauri (Rust) tray app for Windows/macOS/Linux; decided after spike A0 (Electron is the fallback) |
| Reports | CSV (formula-injection-safe), PDF for employee monthly report, Excel via Apache POI |
| Monitoring | Sentry (frontend + backend, PII scrubbed), Actuator metrics, structured JSON logs with correlation IDs |
| Rate limiting | Bucket4j + Redis |
| Testing | JUnit + Mockito + Testcontainers (backend), Vitest/RTL (frontend), Playwright (E2E), REST-assured contract tests |
| Repos | `peoplehub-backend`, `peoplehub-frontend`, `peoplehub-agent`, `peoplehub-infra`, `peoplehub-docs` (Section 16) |
| Hosting | Backend: VM/container host. Frontend: Vercel. Same registrable domain (D10). Decide at Phase B0. |
| Containerization | Docker (multi-stage builds), `docker-compose` for local/dev/CI parity (D16, 16.4) |

---

## 2.1 Organization Bootstrap & Tenant Boundary

### 2.1.1 Organization identity
- `organization.id`: UUID/ULID primary tenant key; never accepted from an untrusted client as authority.
- `organization.name`: display name shown throughout the workspace.
- `organization.login_key`: normalized unique key used to resolve the organization during login. The UI may label this field **Organization** or **Organization name** to keep the experience simple.
- `organization.status`: `PENDING_VERIFICATION | ACTIVE | SUSPENDED | CLOSED`.
- All tenant-owned business tables carry `organization_id` directly or have an unambiguous tenant path enforced by schema/repository rules.
- JWT claims include the authenticated `organization_id`, employee/user id and role. The server derives tenant scope from the authenticated principal, never from a body/query parameter after login.

### 2.1.2 Absolute isolation rule
For Organization A, all of the following are forbidden with respect to Organization B:
- seeing B's name, login key, employees, departments, calendar, attendance, leave, reports, audit/security information or settings;
- searching/autocompleting B or its people;
- learning the total number of organizations hosted by PeopleHub;
- accessing another tenant by changing a URL/UUID/body field;
- receiving another tenant's notification, email context, SSE event, cached object, export row, scheduled-job result or file;
- inferring tenant existence from login error wording/timing.

Cross-tenant identifiers are treated as **not found inside the caller's tenant**, not “forbidden because it belongs to another company.” Admin and Super Admin power never crosses the organization boundary.

### 2.1.3 Public organization registration
Public route: `/register`.

Fields:
1. Organization name
2. Organization timezone
3. Founding Super Admin full name
4. Company email
5. Password
6. Confirm password
7. Terms/privacy acknowledgement

On successful submission:
1. Validate and normalize organization identity.
2. Validate founder email/password and breached-password policy.
3. In one DB transaction create the Organization plus founder Employee/Auth identity:
   - `role = SUPER_ADMIN`
   - `status = PENDING_VERIFICATION`
   - founder belongs to the newly created `organization_id`.
4. Send a single-use, short-lived email-verification link through the outbox.
5. Do **not** issue a normal authenticated workspace session before verification.
6. Email verification activates the organization and the founder as its first `SUPER_ADMIN`. MFA is not a registration or verification step; the founder chooses the organization's MFA policy during setup and may enroll whenever that policy offers MFA (8.3).
7. Run first-time organization setup.
8. Mark onboarding complete and enter the **real Super Admin dashboard**.

The founder's password is their own private credential. PeopleHub never exposes it to another user and never asks the founder to create passwords for invitees.

### 2.1.4 First-time organization setup
After verification, the founder completes a short setup flow using the real organization settings model:
- Organization: display name, timezone.
- Work schedule: weekly offs, expected daily hours.
- Attendance safeguards: warning/cap defaults and desktop-agent policy.
- Leave basics: enabled leave types/policy defaults.
- Security: choose the organization's MFA policy (default `DISABLED`, 8.3), unless the policy is `DISABLED` optionally enroll the founder's own MFA, and review security defaults.
- Team: optional invitation of the first Admin/employees.

Completing setup routes to the standard Super Admin dashboard. There is no permanently separate “setup dashboard.”

### 2.1.5 Invitation model
There is **no public Employee/Admin registration**.

Authorized Admin/Super Admin creates an Employee invitation. Super Admin may also create a direct Admin invitation. The invitee receives an email containing a random, single-use, expiring token. The activation page displays the inviting organization and role, then lets the invitee privately set their own password.

Activation:
- verifies token and intended tenant;
- never allows changing the invitation's organization or role;
- sets password;
- activates the Employee identity;
- applies the organization's MFA policy (8.3): MFA enrollment is required before workspace access only when the policy requires it for that person; otherwise MFA remains optional;
- shows the one-time welcome experience;
- routes to the role-appropriate dashboard.

### 2.1.6 Login resolution
All roles use the same form:
**Organization + company email + password**.

The client never asks “Employee/Admin/Super Admin?”. Role is server-authoritative. Login resolves the normalized organization key, finds the account only inside that tenant, verifies password/status, performs MFA when required, and returns the authenticated identity. Failure uses one generic message such as **“We couldn't sign you in with those details.”** with timing/rate-limit protections.

### 2.1.7 Deactivation and access loss
When an Admin/Super Admin deactivates an Employee:
- status becomes `DEACTIVATED`;
- all active refresh-token families/sessions are revoked;
- future login and API/SSE access are denied;
- paired device authorization is revoked/disabled as appropriate;
- pending approval responsibility is reassigned using the fallback rules;
- historical attendance, leave, corrections, reports and audit references remain;
- the person is excluded from active headcount/live attendance and future operational choices.

Normal Employee exit is **deactivation, not hard delete**. The special Admin tombstone/anonymization rule in D7 remains a distinct, step-up-protected operation.

---

## 3. Org Structure, Roles & Permissions

### 3.1 Departments / Teams

Name-only groupings (no nesting). Each employee belongs to **at most one** department at a time. Used for filtering attendance/leave/reports, for coverage warnings later, and — new in v5 — as a scoped visibility boundary between employees. Not an approval hierarchy: an approver is chosen per Section 7 regardless of department.

**Admin/Super Admin capabilities (Employee has none of these):**
- **Create a department** (name, optional description). Name unique per org.
- **Rename or delete** a department. Deleting a non-empty department requires confirmation; on delete, its employees' `department_id` is set to `null` (unassigned) — employees are never deleted or deactivated as a side effect.
- **Add existing employees to a department**, one or in bulk: a multi-select "Add members" picker over the full employee list (any employee, regardless of current department — moving them removes them from their old one, since membership is single).
- **Remove an employee from a department** (sets `department_id` to `null`); the employee still exists and keeps their history, they simply lose that department's peer visibility.
- **View a department's full member list with full employee details** (same as everywhere else in the console — department membership never restricts what Admin can see).

**Assigning a department during employee creation:** the "Add employee" form includes a **Department** dropdown populated from existing departments (plus "No department"). Picking one sets `department_id` at creation, so a new hire lands in the right department immediately without a separate "add to department" step. The same dropdown appears on the employee-edit form, so Admin can move someone between departments at any time (this **replaces** their prior department — see membership rule above).

**Employee-side visibility (new in v5 — a real access boundary, not just a filter):**
- An employee who belongs to a department can see a simple **roster of names** of the other employees in that same department — name only (no email, phone, employee code, attendance, leave balance, or any other field).
- They **cannot** see any other department's roster or member names at all.
- An employee with no department assigned sees no roster.
- This is enforced in the **service layer** (3.2): the department-roster endpoint takes the caller's own `department_id` from their token, never a parameter, and returns only names for that department — it never accepts an arbitrary `department_id` from an employee. Admin/Super Admin calling the equivalent admin endpoint get full details for any department by explicit id.

### 3.2 Permissions

| Capability | Super Admin | Admin (HR) | Employee |
|---|---|---|---|
| Log in, check in/out, apply leave, view own balance | ✅ | ✅ | ✅ |
| **View own daily / weekly / monthly reports incl. previous months, download own report** | ✅ | ✅ | ✅ |
| Pair / view / remove own desktop-agent devices | ✅ | ✅ | ✅ |
| View / revoke own active sessions | ✅ | ✅ | ✅ |
| View all employees' attendance, leave, reports | ✅ | ✅ | ❌ |
| **View names of employees in own department only** (no other fields, no other departments) | n/a (see full detail row) | n/a (see full detail row) | ✅ own department only |
| **View full details of every employee in every department** | ✅ | ✅ | ❌ |
| **Create / rename / delete departments; add or remove members; assign department on employee creation or edit** | ✅ | ✅ | ❌ |
| Approve leave and correction requests | ✅ | ✅ | only if set as that request's approver |
| Edit an employee's attendance record (reason mandatory, audited) | ✅ | ✅ | ❌ |
| Manage employees (add / update / deactivate / reactivate / bulk import) | ✅ | ✅ | ❌ |
| Manage departments, holidays | ✅ | ✅ | ❌ |
| Configure org settings (Section 11) | ✅ | ✅ (except security settings, Super Admin only) | ❌ |
| Lock a month | ✅ | ✅ | ❌ |
| **Unlock** a month | ✅ | ❌ | ❌ |
| Revoke any employee's device / reset an employee's MFA | ✅ | ✅ | ❌ |
| Add / deactivate / reactivate / delete Admins; reset an Admin's MFA | ✅ | ❌ | ❌ |
| Export reports | ✅ | ✅ | own only |
| Security dashboard, audit-log viewer | ✅ | ❌ | ❌ |

**Rules that apply everywhere**
- Every Admin and Super Admin is also an Employee record. Role is a relation, not a separate identity.
- Employees are **never hard-deleted**, only deactivated. History stays intact.
- Authorization is enforced in the **service layer**, not only controllers. Self-service endpoints take the employee id from the token only, never from a parameter or body (prevents IDOR). Every endpoint has a negative authorization test.
- An Admin can never modify a Super Admin.
- Nobody may approve or edit their own leave or attendance (D9).

### 3.3 Admin lifecycle (Super Admin only)
- **Add Admin:** promote an existing Employee. No separate signup. MFA is not a universal requirement of promotion: if the organization's MFA policy requires MFA for the promoted person, they enroll at their next sign-in, before any Admin function (8.3).
- **Deactivate Admin:** reversible; blocks login; revokes all sessions and tokens; reassigns their pending approvals (7.2).
- **Reactivate:** restores prior access exactly as it was; MFA still applies.
- **Delete Admin (irreversible):** the employee row becomes a **tombstone**: name/email/phone/password/MFA/devices destroyed, email freed for reuse, display name becomes "Former employee #id". Attendance, leave and audit rows are **retained** and point to the tombstone; they never carry to a re-onboarded account. Requires step-up auth (8.3) and a typed confirmation.
- **Last Super Admin protection:** the last active Super Admin cannot be deleted, deactivated or demoted. A Super Admin cannot delete or deactivate themselves. Ownership transfer is an explicit flow. The system warns until 2 or more Super Admins exist.
- **Break-glass recovery:** documented CLI/runbook procedure (needs server access) to restore a Super Admin if all are locked out; the action is audit-logged.


**v9 addition — direct Admin invitation:** Super Admin may invite a brand-new person directly as Admin instead of first activating them as Employee and then promoting them. The backend still creates one Employee identity with `role=ADMIN`, `status=INVITED`, tenant-bound to the caller's organization. Invitation acceptance sets the invitee's private password; MFA enrollment before first Admin access is required only when the organization's MFA policy requires it for that person (8.3). Existing-employee promotion remains supported and requires step-up authentication. Neither path can target another organization.
### 3.4 Bulk employee import (CSV)
Columns: employee code, name, email, department (optional), join date. Rules:
- Validate **row by row**: malformed email, duplicate email in file or in org, unknown department, missing join date.
- Import valid rows, reject invalid ones, show a **per-row result report** (downloadable). Never silently skip or abort the whole batch.
- Limits: file size, max rows, UTF-8 only.
- **CSV/formula injection defence:** cells starting with `= + - @` (or tab/CR) are rejected on import and escaped on every export.
- Each imported row triggers the standard invite email via the outbox. Step-up auth required to start an import. Audit-logged with row counts.

### 3.5 Employee Directory (Admin / Super Admin)

A first-class admin screen — not just an implied side-effect of "employees CRUD" — that lists **every** employee in the org with full detail-level access (D-row in 3.2 already grants this; this section says what the screen actually does):

- **Columns:** name, employee code, email, department, role, status (Invited / Active / Deactivated), join date, and **today's hours** (from `attendance_day`, live if a session is open) so Admin/Super Admin can see hours without leaving the directory (5.6). Row actions: view/edit, deactivate/reactivate, move to a different department, reset MFA (with step-up auth, 8.3).
- **Search (D20) — by name:** one search box, server-side, case-insensitive, debounced (300 ms `[confirm]`), matching on name, email, or employee code. As the admin types, matching employees appear in a results list/dropdown; **selecting a result (or a single unambiguous match) opens that employee's full profile directly** — same detail card as clicking a row (personal fields, department, attendance/leave summary, devices, sessions). Multiple matches simply narrow the table rather than auto-opening one.
- **Filter — by department (D20):** a department chip row or dropdown (not the same input as name search) narrows the current list to **only the employees in that department**, combinable with the name search and with role/status filters. Selecting "All departments" clears the narrowing. This is the same filter/endpoint the department's own "View roster" (3.1) uses, so the two screens never drift apart.
- **Sorting:** any column, via the standard `sort` param (13). Default sort: name ascending.
- **Pagination:** standard `page`/`size` contract (13), default page size 25, so the screen stays fast at any org size instead of loading the whole employee table client-side.
- Feeds the "Add members" picker in 3.1 and the department dropdown on create/edit — same underlying paginated/searchable list component, reused rather than rebuilt.

---

## 4. Attendance (the core module)

### 4.1 Golden rules (non-negotiable)

| # | Rule |
|---|---|
| R1 | **Check-in creates an open session on the server.** That open session is the only thing that "runs the clock". |
| R2 | The session **keeps running** regardless of: switching tabs, closing a tab, closing the browser, logging out of the app, access/refresh token expiry, network loss, laptop sleep/hibernate/lock/idle/screen-off, backend deploys or restarts. Nothing on the browser side ever ends a session. |
| R3 | A session ends **only** by: (a) employee manual check-out; (b) verified OS shutdown/restart/logoff of the bound computer (agent), `SYSTEM_SHUTDOWN`; (c) unclean shutdown detected on next boot, `SYSTEM_SHUTDOWN_ESTIMATED`; (d) employee deactivation; (e) admin edit or approved correction; (f) safety cap (D4). Midnight **splits** a session but does not end the work. |
| R4 | **Rest counts.** Time while the computer is asleep, locked or idle is counted. |
| R5 | **The server is the only clock.** The UI timer is display-only and always derived from the server's `check_in_at`. |
| R6 | **Every non-manual end is visible and labelled** everywhere (today card, daily/weekly/monthly reports, admin views, exports, notification), e.g. *"Checked out automatically at 6:42 PM: computer shut down."* |

### 4.2 Session model (replaces v3's one-record-per-day)
A day can legitimately hold several sessions (morning, shutdown, re-check-in; or a midnight split), so v3's one-record-per-day + per-day idempotency cannot represent reality.

- `attendance_session`: one row per continuous stretch of work (Section 12).
- **At most one open session per employee**, enforced by a partial unique index (`WHERE check_out_at IS NULL`). This gives idempotency and cross-tab/cross-device consistency for free.
- **No overlapping sessions** for the same employee, enforced by an exclusion constraint on `tstzrange(check_in_at, check_out_at)`.
- All instants stored as UTC `timestamptz`. **Durations are computed from instants**, never from wall-clock strings, so DST or timezone changes cannot corrupt hours.
- `work_date` = the date of `check_in_at` in the **org timezone**.
- Hours for a day = sum of that day's session durations (seconds). Weekly and monthly totals sum **seconds** and are formatted to HH:MM only at display, so there are no sum-of-rounded errors.
- `attendance_day` is a derived rollup (one row per employee per date) that feeds every report (Section 5). It is recomputed on session close, correction, admin edit, leave decision or holiday change, and reconciled by a nightly job.

### 4.3 Check-in / check-out behaviour
- Timestamps are always set server-side. `Idempotency-Key` header supported; repeat check-in while open returns the existing open session; repeat check-out returns the closed session.
- Check-in on a weekly-off day, holiday or approved-leave day is **allowed** but flagged (`WORKED_ON_OFF_DAY` / `WORKED_ON_LEAVE`). Off-day work feeds comp-off (4.9); leave-day work is surfaced to Admin and the employee for review.
- Optional check-in attributes: `source` (WEB / AGENT / ADMIN / CORRECTION); `work_mode` (Office / Remote / Field) is a booster (Section 18).
- Manual check-out asks no questions; it closes at server time.
- Deactivating an employee closes any open session at that moment (`DEACTIVATION`).

### 4.4 Shutdown detection: the desktop agent

**The limit, stated plainly.** A web page cannot know the computer is shutting down, and the browser is often killed before it could say so. Server-side inference from silence cannot tell a shutdown from sleep, a closed browser or a network drop (and D1 says those must count). So shutdown detection is only possible with a small local program.

**Agent scope (privacy-first, published to employees):** it reports only (1) OS shutdown / restart / logoff events, (2) an "agent is alive" heartbeat, (3) boot identity. It records **no** screen, keystrokes, apps, URLs, files or location. Employees can see every event recorded for their own devices in the app. Legal/HR notice text ships with the agent (DPDP-style transparency); this is not legal advice.

**Org setting `agent_mode`:** `DISABLED` | `ENCOURAGED` (default: banner prompts install) | `REQUIRED` (check-in blocked until a paired device has a fresh heartbeat).

**Pairing.** In the web app: "Add this computer" → one-time 8-character code (5-minute expiry, step-up auth) → the agent registers, generates an **Ed25519 key pair stored in the OS keystore** (Windows Credential Manager/DPAPI, macOS Keychain, Linux Secret Service) and sends only the public key. Devices are listed and removable by the employee (and by Admin). Revoked devices are rejected.

**Events.** Every agent event is signed and carries `device_id`, monotonic `seq`, `occurred_at`, `boot_id`, nonce. The server verifies the signature, rejects replays (unique `(device_id, seq)`), and bounds the time: not after receipt (+2 min skew), not before the session's check-in. The agent corrects its clock using the server-time offset learned during heartbeats.

**Which shutdown closes which session.**
- At check-in, the server **binds** the session to the employee's paired devices with a heartbeat in the last 90 s (an unbound session may late-bind if a device heartbeat arrives within 5 minutes of check-in). Check-in from the agent tray binds to that device.
- A `SHUTDOWN`/`LOGOFF` event marks that device down. The session closes with `SYSTEM_SHUTDOWN` at the shutdown time **when the last bound device is down** (shutting down one of two machines does not check the employee out).
- Unbound sessions (agent not running at check-in) ignore shutdown events.

**No network at shutdown / power cut / crash.** The agent queues the event on disk and sends it at next boot with the original timestamp. If the machine died without an event (power loss, crash), the agent detects a **new `boot_id`** at start and reports `UNCLEAN_SHUTDOWN` with its last locally recorded alive time (written every minute). The server closes the session as `SYSTEM_SHUTDOWN_ESTIMATED` at that time, labelled "estimated (power loss or crash)"; the employee can correct it. Sleep/hibernate resume keeps the same `boot_id`, so it never triggers a close (D1).

**After the close.** The next time the employee opens the app or the agent starts: banner/toast, "Your session ended at 6:42 PM because your computer shut down. Check in again?" (one click). The gap between shutdown and re-check-in is not counted.

**Without the agent** (`DISABLED`, or employee has none): rules R1-R2 and R3(a,d,e,f) still hold; shutdown just cannot end the session. The employee checks out manually, or the safety cap and a correction cover it. The UI shows a soft "install the agent for automatic shutdown check-out" hint.

**Agent hardening:** signed installers/updates, no listening network ports, HTTPS with certificate pinning to the API, least privilege, uninstall = device revoke. **Spike A0 must prove** shutdown/logoff hook reliability per OS (including Windows Fast Startup, macOS power-off notification, Linux `systemd-logind` inhibitor) before the agent is committed to.

### 4.5 Midnight split and safety cap (replaces v3's midnight auto-checkout)
- **Day split job** (org timezone, ShedLock, idempotent, catch-up after downtime): at 00:00 an open session is closed at 23:59:59.999 with reason `DAY_SPLIT` and a continuation session opens at 00:00:00 on the new date (`continued_from_session_id`, shared `chain_root_id`). The employee's timer never stops; reports credit each day correctly. The UI shows one merged entry: "Working since 9:10 AM yesterday".
- **Long-running safeguard:** when a continuous chain exceeds `session_warning_hours` (12), notify the employee (in-app + email, with one-click "check out now" and "I'm still working"). At `session_max_hours` (20) the system closes it as `SYSTEM_MAX_DURATION`, flagged, and the employee can correct within the window. Setting the cap to `null` disables the auto-close (not recommended; Admin sees a flag instead).
- This is the only place v4 departs from "only manual or shutdown"; it exists to stop a forgotten check-out from inflating hours forever.

### 4.6 Client behaviour (frontend contract)
- On every load, reopen, focus and `visibilitychange`: `GET /attendance/today` and `GET /time`. Compute `serverOffset = server_now − client_now`.
- Timer = `client_now + serverOffset − check_in_at` (display only; never stored as truth; never accumulated by ticks).
- Multi-tab: BroadcastChannel notifies other tabs on check-in/out; TanStack Query refetches on focus and polls every 60 s while visible. The server state always wins.
- **No** `beforeunload`, `unload` or `pagehide` handler ever calls check-out; no `sendBeacon` check-out.
- **Logout while working** shows: "You're still checked in. Logging out won't check you out."
- Check-in/out require connectivity. On failure: clear retry UI; never show "checked out" optimistically. **Offline check-out intent:** queued locally with a monotonic-clock estimate; on sync the server accepts the client-estimated time only inside `[check_in_at, receipt]` and not older than 12 h, flags it `CLIENT_ESTIMATED_TIME`, otherwise uses server time and flags it.
- Celebration triggers fire only after a **server-confirmed** success.

### 4.7 Attendance correction / regularization requests
- Employee raises a request: proposed check-in/out for a date (or an existing session), reason (required), optional note. Covers: forgot to check in, forgot to check out, shutdown/cap-estimate is wrong, genuine late punch.
- Goes through the **shared approval engine** (Section 7), same endpoint as leave.
- Rules: only within `correction_window_days` (30) and never in a **locked month**; must not overlap other sessions; cannot exceed 24 h per day; overlap validation vs other `PENDING`/`APPROVED` requests.
- On approval: a `CORRECTION_APPROVED` session is created/updated, `attendance_day` recomputed, comp-off re-evaluated, employee notified, audit-logged.
- Employee can cancel while `PENDING`.

### 4.8 Admin edits
Admin/Super Admin can edit or add a session with a **mandatory reason**; audit-logged with before/after; employee notified; blocked in locked months (Super Admin unlocks). An admin's own record is edited by another admin (D9).

### 4.9 Comp-off trigger
Worked seconds on a weekly-off day (org-configurable) or holiday (`comp_off_on_holidays`) totalling at least `comp_off_min_hours` (default 4 `[confirm]`) earn one comp-off credit. Credit is issued when the **day is finalized** by the nightly job (after the day split), and reversed through the ledger if a correction later removes the qualifying hours.

---

## 5. Reports

### 5.1 Employee self-service: "My Attendance" (Daily | Weekly | Monthly)
Own data only (id from token). One source of truth (`attendance_day` + sessions) so daily, weekly and monthly numbers always agree. Deep-linkable URLs.

**Daily**
- Date picker; every session that day: check-in, check-out, duration, source (Web/Agent), and a **close badge with note** (Manual / Computer shut down / Estimated shutdown / Continued past midnight / Auto-closed after 20 h / Corrected by HR).
- Day total, day type (Worked / Leave / Holiday / Weekly off / Absent / Worked on off-day), flags, linked correction requests, target vs actual (informational; there is no shift enforcement).
- A currently open session appears as "In progress" with the live timer.

**Weekly**
- Week navigator (week start day from org setting, default Monday). One row per day: day type, first in, last out, total. Bar chart per day. Week total, average per worked day, target vs actual, days with flags.

**Monthly**
- **Month picker with every month from the employee's join month to the current month** (previous months always browseable).
- KPIs: expected working days (calendar − weekly-offs − holidays), days present, total hours, average per present day, leave days by type, holidays, off-day work, comp-off earned/used, corrections raised/approved, flagged days.
- Calendar heatmap; day-by-day table; leave summary; list of days needing attention (flags, unresolved auto-closes) with a "raise correction" shortcut.
- **Download** the month as CSV or PDF (own report only; light audit entry).
- Past months are stable but recalculated if a correction is approved: shows an "updated" marker; a **Locked** badge shows when Admin has locked that month.

**Rules:** "present" = worked at least `present_min_minutes` (default 30 `[confirm]`); absence label only for expected working days with no leave/holiday/work, from join date to exit date; totals computed from seconds; p95 report response under 300 ms via the rollup table.

### 5.2 Admin reports
- Attendance summary by employee, department, date range (hours, days present, flags, auto-closes, corrections).
- Live "who is in / on leave / absent" view — see 5.5 for the detailed spec.
- Leave ledger and balance report; comp-off ledger (earned, used, expired, liability).
- Corrections report (raised, decided, turnaround).
- Any employee's monthly report (same layout as 5.1).
- Filters: department, employee, date range. Formats: CSV, Excel (POI), PDF for the monthly employee report.

### 5.3 Export safety
Every admin export is audit-logged (who, what filters, when, row count). All CSV/Excel output escapes formula-leading characters. Exports are streamed for large ranges and rate-limited.

### 5.4 Month lock
Admin can lock a month (payroll cut-off): no corrections, edits or backdated leave inside it. Only Super Admin can unlock (audited, reason required). Locked state is visible to employees.

### 5.5 Live Attendance Dashboard (Admin / Super Admin only)

A single real-time screen answering "who's actually working right now" — this is a **view**, computed on read from `attendance_session`, `attendance_day`, `leave_request` and the presence heartbeat (8.4); it introduces no new attendance state and changes nothing about what starts or stops a session (still only D1/4.1/R3).

Every employee for today's `work_date` falls into exactly one bucket:

| Bucket | Definition |
|---|---|
| **Clocked In — Active** | Has an open session (`check_out_at IS NULL`) **and** a presence heartbeat within `presence_grace_minutes` (default 10, Section 11). |
| **Clocked In — Idle** | Has an open session but no heartbeat within `presence_grace_minutes`. Still fully "working" for attendance purposes (D1: rest counts) — this label is informational only, it never affects hours or pay. |
| **Not Clocked In (yet)** | No session at all for today's `work_date`, and not on approved leave/holiday/weekly-off. |
| **Clocked Out** | Has at least one session for today that is closed, and no currently open session; shows the close reason/badge from 4.1/5.1 (Manual / Shutdown / Estimated / Auto-closed / Corrected). |

Employees on approved leave, on a holiday, or on their weekly off are shown in a separate **On leave / Off today** group rather than "Not Clocked In", so Admin isn't chasing people who aren't expected to work.

**UI:** four (or five, with the leave/off group) columns or filterable tabs, employee name + department + last-event time in each; department filter and name search follow the same **D20** rule as the Directory (3.5) — name resolves to the employee's detail card, department narrows the bucket's list; counts per bucket at a glance. Updates in real time via the existing SSE channel (9.2) with a polling fallback, so Admin doesn't need to refresh. Clicking an employee opens their daily report (5.1).

### 5.6 Role Dashboards (Employee / Admin / Super Admin)

The console **home screen** each role lands on after login — distinct from 5.1 (own reports), 5.2 (admin reports) and 5.5 (the full live-attendance screen), all of which it links out to rather than duplicates.

**Employee dashboard**
- Persistent greeting (10.3).
- **Live timer widget:** if the employee has an open session, a running stopwatch (elapsed time this session) plus **Check out**; if not, a **Check in** button. This is the *only* place in the app a personal running timer is the headline element — mirrors the open session already tracked by 4.1–4.6, purely a read/act view over it, no new state.
- Today/this-week snapshot: hours so far today, days present this month, current leave balances (6.2) — links into 5.1 for the full report.
- **"`{n}` of your requests are pending"** tagline (D19): counts the employee's own `PENDING` leave + correction requests (6.3, 4.7). Visible only when `n > 0`. Clicking it opens "My requests" (the employee-facing list backing 6.3/4.7) filtered to `PENDING`. Approving/rejecting is not available here — an employee's own requests are decided by someone else (D9) — this is a status link, not an action queue.
- Org calendar preview (next few `calendar_event` rows, 6.6), read-only.

**Admin / Super Admin dashboard**
- Persistent greeting (10.3) — **no personal timer widget here** (D18); the admin's own clock-in/out lives on their personal "My Attendance" screen (5.1), same as any employee, one click away, not on the console home.
- **Org hours widget:** today's headcount vs. clocked-in count, total hours logged so far today across the org, and average hours/employee so far today, computed the same way as 5.2's attendance summary (so the number on the dashboard and the number in the report never disagree). Links into 5.2 and into the Directory's per-employee hours column (3.5) for the breakdown by person.
- Live-attendance bucket summary (counts only: Active / Idle / Not yet / Clocked out / On leave) with a link into the full 5.5 screen.
- **"`{n}` requests need your approval"** tagline (D19, 7.5): counts `PENDING` `approval_request` rows where this Admin/Super Admin is the current resolved approver (7.2) — same query the reminder/escalation job (7.2) already runs, reused rather than duplicated. Visible only when `n > 0`. Clicking it opens the Approvals queue (7.1) filtered to `PENDING`, pre-scrolled to the first item. Approving or rejecting from that queue removes the item immediately; `n` is recomputed on every decision (via the same real-time channel as 9.2/5.5) so the tagline shrinks live and **vanishes once `n` reaches 0** rather than sitting at "0 requests need your approval."
- Org calendar preview (6.6) with a "+ New entry" shortcut for Admin/Super Admin.

---

## 6. Leave Management

### 6.1 Leave types and policy
Data-driven `leave_type` table (casual, sick, comp-off, optional LOP / leave-without-pay). Accrual amount/period, carry-forward, expiry, `allow_negative`, `requires_document` (booster) are per type. Policy changes are **versioned with an effective date**; changing policy never rewrites past ledger entries. Half-day is deferred, but durations are decimals from day one.

### 6.2 Ledger-based balances (fixes v3's single balance number)
`leave_balance` is a cache of `sum(leave_ledger)`. Every change is a ledger row: `OPENING`, `ACCRUAL`, `PRORATA_ACCRUAL` (mid-year joiners), `DEDUCTION`, `RESTORE`, `CARRY_FORWARD`, `EXPIRY`, `ADJUSTMENT`, `COMP_OFF_CREDIT`, `COMP_OFF_USE`. Unique `(ref_type, ref_id, entry_type)` makes deductions idempotent. Employees see their own ledger.

### 6.3 Applying
- Type + date(s) + approver (explicit or default). Balance and projected-after-request balance shown before submit.
- Validation: overlap with `PENDING`/`APPROVED` leave or corrections; enough balance (or the type allows negative/LOP); date range containing only off-days/holidays; backdating within `leave_backdate_window_days` (7) and never into a locked month; optional `leave_min_notice_days`.
- Weekly-offs and holidays inside the range are not deducted (weekly-off days come from org settings).
- Created as `PENDING`; approver chosen per Section 7.

### 6.4 Lifecycle
- Edit or cancel while `PENDING`.
- **Cancel after approval:** future-dated approved leave can be cancelled by the employee (approver notified; balance restored via ledger `RESTORE`). Past/started leave needs Admin. Both audit-logged.
- On approval: ledger deduction and calendar update in **one transaction** with the status change. On rejection: balance untouched.
- Working on an approved-leave day is flagged (4.3); Admin can adjust.

### 6.5 Deactivation / exit effects
Pending requests of a deactivated employee are auto-cancelled. Pending requests **assigned to** a deactivated approver are reassigned (7.2). Leave balances stay readable to Admin for settlement.

### 6.6 Org calendar (holidays & events)

One org-wide calendar, one table (`calendar_event`, Section 12), two entry types, so there is a single UI and a single API instead of a separate "events" system bolted on next to holidays.

- **`HOLIDAY`** — behaves exactly as in v4: `mandatory` flag, org-wide, feeds `attendance_day`/leave-day math (weekly-off/holiday days aren't deducted from leave, comp-off eligibility, etc.). Editable years ahead. Whether some of the 5 yearly extra holidays are optional/restricted is still an open item (Section 19).
- **`EVENT`** — new in v5: a general dated entry (title, description, optional start/end time, optional location/note) for things like team outings, all-hands, maintenance windows or announcements. **Purely informational** — it never affects attendance, leave-day math or comp-off.

**Admin/Super Admin (Employee has none of these):**
- **Create** an entry by picking a date from a calendar-style date picker in the console, filling in title/description/type, and for `HOLIDAY` the `mandatory` flag.
- **Edit** any entry (date, title, description, mandatory flag) — changes to a `HOLIDAY` entry's date or removal trigger `attendance_day`/leave-day recalculation for affected future dates, same as v4.
- **Delete** any entry, with confirmation; deleting a past `HOLIDAY` does not retroactively rewrite already-locked months (Section 5.4).
- All create/edit/delete actions are audit-logged (who, what changed, when).

**Employee (read-only, cannot create/edit/delete anything on the calendar):**
- Sees the full calendar (month/list view) with every `HOLIDAY` and `EVENT` entry and its full details (title, description, date/time, location/note) — nothing is hidden from employees on the calendar itself; the read-only restriction is about **modification**, not visibility.
- No employee action ever writes to `calendar_event`; the edit/delete controls simply don't render for the Employee role, and the underlying endpoints reject non-Admin callers server-side (belt-and-braces with 3.2's service-layer rule).

---

## 7. Approval Engine (leave + corrections)

### 7.1 One engine, two request types
`approval_request` (type LEAVE | CORRECTION) with `version` for optimistic locking, status `PENDING → APPROVED | REJECTED | CANCELLED | AUTO_CANCELLED`. Two triggers, one shared decision service: **console** and **email**.

### 7.2 Choosing and keeping an approver
Resolution chain for approver: selected approver → employee's default approver → org default approver → any Super Admin/Admin who is not the requester. Skip anyone who is: the requester (no self-approval), deactivated, or on approved leave covering the submission date. If nobody is eligible, the request stays `PENDING` with `NO_ELIGIBLE_APPROVER` and Super Admin/Admin are alerted.
- **Reminders** after `approval_reminder_days` (2); **escalation** to the next in chain after `approval_escalation_days` (4), repeating.
- **Reassignment** when an approver is deactivated/deleted: pending items move down the chain automatically, with notification.
- Approvers get an optional daily digest of pending items.

### 7.3 Safe email approvals (fixes the scanner problem)
- The email contains a "Review request" link with a signed, single-purpose token (hashed at rest, bound to `(request, approver)`, expires in `email_approval_token_hours`, 72).
- `GET` the link → **read-only confirmation page** showing requester, dates, reason, balance impact. **No state change on GET**, so mail scanners and link previewers cannot trigger anything.
- Approve/Reject buttons submit `POST` with CSRF protection; the token is consumed **on decision**, not on view.
- Already-decided or expired → friendly page: "Already approved by [name] on [date]". Never an error, never a second balance change.
- Optional `email_approval_requires_login` forces sign-in first. IP and user-agent are recorded on every email decision.

### 7.4 Concurrency
Decision requests carry the `version` seen by the client; a stale version returns "already decided". The decision, status change, ledger entries, session/attendance changes and notifications are written in one transaction (notifications via the outbox).

### 7.5 Live pending-approval count

The count backing both dashboard taglines (5.6, D19) is a single read query — `COUNT(*) FROM approval_request WHERE status = 'PENDING' AND resolved_approver_id = :callerId` for Admin/Super Admin, and `WHERE status = 'PENDING' AND requester_id = :callerId` for an employee's own view — never a separately maintained counter, so it can never drift from the actual queue. It rides the same SSE channel as the Live Attendance Dashboard (9.2, 5.5): any decision (by this approver, by someone later in the fallback chain, or a cancellation by the requester) pushes a fresh count to every open dashboard tab for the people it affects, so the banner updates without a manual refresh and disappears the instant `n = 0`.

---

## 8. Auth, Sessions & Security Design

### 8.1 Tokens and cookies
- Access token: 15 minutes, held **in memory** only (never localStorage), `kid`-based signing-key rotation.
- Refresh token: httpOnly, Secure, SameSite=Lax/Strict **cookie, same-site with the API (D10)**; rotating with reuse detection (a reused old token revokes the whole family); **sliding 30 days plus an absolute cap of 90 days** `[confirm]`, then re-authentication regardless of activity.
- CSRF: refresh/logout endpoints require a CSRF token (double-submit) plus Origin check. CORS allows only the app origin.
- Silent refresh; expiry or refresh never touches attendance (R2).

### 8.2 Account flows
- **Register organization:** public founder-only bootstrap described in 2.1.3. Successful registration creates one tenant plus its founding `SUPER_ADMIN`; it does not create a general public user-signup path.
- **Verify founder email:** single-use, expiring verification token; resend is rate-limited and always safe against enumeration.
- **Founder MFA:** not part of registration or verification. The founder becomes the Super Admin first, sets the organization's MFA policy, and may enroll whenever that policy offers MFA (8.3).
- **First-time organization setup:** 2.1.4; completion enters the real Super Admin dashboard.
- **Login for every role = Organization + company email + password.** No role picker. Generic errors and consistent timing prevent organization/account enumeration.
- **Invite/activation:** single-use, expiring, resend-supported set-password link. Invitation fixes organization + role; invitee cannot alter either.
- **Direct Admin invitation:** Super Admin only; new identity is still an Employee record; MFA before the first Admin session only when the organization's MFA policy requires it (8.3).
- **Existing Employee → Admin promotion:** Super Admin only, step-up protected. MFA enrollment is not a precondition of promotion; when the organization's MFA policy requires MFA for the promoted person, they enroll at their next sign-in, before any Admin function.
- **Forgot password:** organization + email input, single-use short-lived token, always-same response, rate-limited per account/IP, all sessions revoked on reset.
- Change password: requires current password; revokes other sessions.
- Password policy: minimum length + complexity + **breached-password check** (k-anonymity API or offline list).
- Lockout with exponential backoff per tenant-account and per IP; login attempts recorded without leaking tenant existence.
- Sessions page: list devices/browsers, revoke one or all others.
- **Deactivation:** blocks login immediately and revokes sessions/tokens while retaining historical records (D26).

### 8.3 MFA and step-up
- TOTP authenticator apps; 10 single-use recovery codes. **MFA is recommended but not globally mandatory: the organization's MFA policy decides enforcement (D6).**
- Organization MFA policy, set by a Super Admin in the security settings (changing it is step-up protected and audited):
  - `DISABLED` (default for a new organization): MFA is off. Nobody is required, enrollment is not offered, and no MFA reminders are shown.
  - `OPTIONAL`: MFA does not block login. Any user may enable MFA voluntarily, and security reminders may encourage enrollment.
  - `REQUIRED_FOR_ADMINS`: covers Admin and Super Admin. Employees are not covered.
  - `REQUIRED_FOR_SELECTED_USERS`: covers the users a Super Admin selects. Other users are not covered.
  - `REQUIRED_FOR_ALL`: covers every user.
- Under a `REQUIRED_*` policy, enforcement applies only to the users the policy covers: a covered user who has not enrolled receives no session until MFA enrollment is completed. Users the policy does not cover are treated as under `OPTIONAL`.
- A user who has enrolled is always challenged at sign-in, whatever the policy; changing the policy to `DISABLED` does not remove an existing enrollment.
- MFA reset only by an authorised role (Employee→Admin/Super Admin; Admin→Super Admin), audited, and it forces re-enrolment when the policy requires MFA for that user.
- **Step-up auth** (fresh password, plus a TOTP or recovery code when the user has MFA enrolled, within 5 minutes) for: delete Admin, promote/demote roles, bulk import, unlock month, MFA reset, export of the full org, changing security settings.

### 8.4 Presence (unchanged in spirit)
Presence is purely cosmetic and never drives attendance or auth: heartbeat only while the tab is visible/focused, **minimum 10-minute grace** before "away", multiple tabs may heartbeat independently. Feeds the Admin "who's online" widget and the Active/Idle split in the Live Attendance Dashboard (5.5) — it never changes an employee's actual hours.

---

## 9. Notifications & Email

### 9.1 Events (each mirrored in-app and, where relevant, by email)
Employee invited/activated; password reset; leave/correction submitted (to approver, with fallback), approved/rejected (to employee); approval reminders/escalations; leave balance reminders; comp-off nearing expiry; admin added/deactivated/reactivated; bulk-import result; **session ended by shutdown / estimated / safety cap; long-running warning**; month locked/unlocked; new device paired; email-send failures (to Admin).

### 9.2 Reliability
- **Transactional outbox:** the email row is committed in the same transaction as the business change, so a crash cannot lose it.
- Retry with backoff (3 attempts over minutes); every outcome in `email_send_log` (SENT / FAILED / RETRYING); failures surfaced to Admin (e.g. "invite to jane@company.com failed") and on the security dashboard.
- **Bounce and complaint webhooks** feed a suppression list; suppressed addresses are shown to Admin so a new hire is not silently unreachable.
- Domain authentication (**SPF, DKIM, DMARC**) verified before go-live; clear sender identity `PeopleHub <notifications@your-domain>`.
- The in-app bell (unread count, real-time via SSE) mirrors every email event, so nothing is missed if mail is delayed or filtered.

### 9.3 Preferences
Per-user, per-type email/in-app toggles. Security- and approval-critical types cannot be fully disabled.

## 10. Celebration, Welcome & Greeting UI

### 10.1 Clock-in / check-out celebration
Confetti + pre-recorded audio on server-confirmed clock-in; distinct cheer on check-out after more than 8 hours; org toggle (`sound_enabled`) plus per-user mute; swappable config-driven assets. Respects `prefers-reduced-motion`. Audio starts only after the click that triggered it (browser autoplay rules).

### 10.2 Post-activation welcome screen (D17)
The very first time an employee completes activation (sets their password, 8.2) — and only then — they land on a one-time full-screen welcome/congratulations page before the normal dashboard:

- **Content:** "🎉 Welcome to `{APP_NAME}`, `{firstName}`!" plus a short, config-driven onboarding blurb (where to check in, where their reports live, an "install the desktop agent" nudge if `agent_mode` isn't `DISABLED`). Confetti/animation reuses the celebration assets from 10.1, respecting `prefers-reduced-motion` and `sound_enabled`/mute the same way.
- **One "Get started" button** takes them to the normal dashboard.
- **Shown exactly once per employee**, driven by `employee.welcome_seen_at` (nullable timestamp, Section 12): `null` → show the welcome screen and redirect there straight after activation login; the "Get started" click calls `POST /me/welcome/ack` (13), which sets `welcome_seen_at` to server time. From then on the employee always lands on the normal dashboard — this is a one-time moment, not a page they can be sent back to.
- Admin does **not** get a separate variant — an Admin is also an Employee record (3.2's rule) and sees the same welcome screen on their own first activation.

### 10.3 Persistent "Hello, {firstName}" greeting
Every authenticated page in the app shell (employee and Admin alike) shows a small, persistent greeting — sidebar or top bar, per the design system — reading **"Hello `{firstName}`, welcome to `{APP_NAME}`"** (the exact copy is a config string like everything else user-facing, D-style with `APP_NAME`; a shorter "Hello `{firstName}`" variant can be used in tight header space, e.g. mobile).

- **`{firstName}` is never re-parsed by the frontend.** The backend derives it once from the employee's stored `name` (12) — first whitespace-delimited token, trimmed, falling back to the full `name` if there's no space (single-word names, no surname on file) — and returns it as `firstName` on `GET /me` (13). This keeps the parsing rule in one place instead of every screen inventing its own "split on first space" logic (and getting it wrong for names with a title, multiple given names, or non-Latin scripts).
- The app shell fetches `GET /me` once per session (cached client-side in memory for the session, not written to Redis domain cache since it's per-employee, not org-wide) and reuses `firstName` everywhere the greeting appears — the welcome screen (10.2), the shell header/sidebar, and any other "Hi, {firstName}" spot the design calls for.
- If an Admin edits an employee's `name` later, the greeting picks up the new `firstName` on their next `GET /me` (next login or session refresh) — no separate "display name" field to keep in sync.

---

## 10.4 Production UI / Visual Acceptance Contract (v9)

The four supplied prototypes are the **approved visual direction**:
- Landing prototype: `landing_peopleHub.html`
- Login prototype: `login_peopleHub.html`
- Organization registration prototype: `register_peopleHub.html`
- End-to-end dashboard prototype: `dashboard_peopleHub.html`

They are reference artifacts only. **Do not ship these static HTML files as the application implementation.** Reproduce the approved composition, spacing, hierarchy, palette, motion and responsive behavior in the specified frontend stack.

### 10.4.1 Frontend implementation stack
- Next.js App Router + TypeScript.
- Tailwind CSS with design tokens in the Tailwind/theme layer.
- TanStack Query for server state; generated/typed API client from frozen OpenAPI.
- Framer Motion for React-native page/micro interactions where motion adds meaning.
- GSAP only for exceptional landing-page cinematic sequences that Framer Motion does not express cleanly; lazy-load it and respect reduced-motion.
- AOS is **not required in production**; replace prototype AOS reveals with Framer Motion/intersection-observer patterns.
- Lucide (or one approved line-icon set) at consistent 1.5–2px stroke.
- No jQuery, no ad-hoc DOM manipulation, no inline prototype JavaScript as application architecture.

### 10.4.2 Design tokens
Approved family:
- Warm ivory background: `#F4F0E8` / `#F5F1E9`
- Paper/off-white: `#FFFDF8` / `#FFFDF9`
- Deep navy: `#172B4D` / `#142640`
- Royal/product blue: `#4169E1` / `#5271E8` / `#5576E8`
- Soft sky: `#DCE8FF`
- Coral: `#FF6B5E` / `#FF7567`
- Peach: `#FFE0D9`
- Lime: `#DCEBA8` / `#E3EDB7`
- Gold/yellow accent: `#FFD86B` / `#F2C867`
- Success green: approximately `#50B989`

Typography:
- Premium editorial/display headings: DM Serif Display where the auth/dashboard reference uses it; Syne may remain on the marketing landing where already approved.
- UI/body/data: Plus Jakarta Sans (or the approved geometric sans token).
- Timers/data counters: JetBrains Mono or tabular-number variant.
- Never mix fonts arbitrarily page-by-page; expose semantic font tokens.

Surface language:
- translucent light cards around `rgba(255,255,255,.70–.82)`;
- `backdrop-filter: blur(12–18px)` where supported;
- 1px low-contrast glass border;
- layered ambient shadows, not harsh material shadows;
- 20–24px premium card radius on major surfaces;
- gradient-filled chart bars with rounded tops and restrained hover indicators;
- emerald live-status pulse for genuinely live state;
- dark navy sidebar with subtle active gradient/fill and uniform line icons;
- numbers use `font-variant-numeric: tabular-nums`.

### 10.4.3 Motion
Motion is subtle and functional:
- auth/landing ambient aurora/orb/grid motion;
- gentle float/parallax on decorative product cards;
- 200–350ms page/card transitions;
- button sheen/press feedback;
- chart hover/tooltips;
- live pulse only for live status;
- no constant distracting movement inside operational tables/forms;
- `prefers-reduced-motion` disables/reduces all nonessential animation.

### 10.4.4 Landing acceptance
The production landing must preserve the approved PeopleHub identity, warm editorial palette, organic background shapes, realistic product preview, feature/workflow storytelling, CTA and footer. Primary actions:
- **Sign in** → `/login`
- **Create organization / Get started** → `/register`
Footer retains **PeopleHub · Personal project** and **Designed & developed by Maneesha Sangam**.

### 10.4.5 Login acceptance
The production login visually matches the supplied premium split-screen experience:
- left cinematic/product panel on desktop; simplified/hidden on small screens;
- animated navy environment, grid/aurora/orbit accents, live-workday/product preview;
- calm ivory auth panel;
- fields: **Organization, Work email, Password**;
- forgot-password and password-visibility controls;
- no role selector;
- “New to PeopleHub? Create your organization” → `/register`;
- generic auth failure; loading, disabled, validation, MFA challenge and rate-limit states must share the same visual system.

### 10.4.6 Registration acceptance
The production registration visually matches the supplied registration prototype:
- premium split layout;
- explicit copy that the registrant becomes the **founding Super Admin**;
- setup-journey panel: Create organization → Verify email → Set up workspace (including the MFA policy) → Enter PeopleHub;
- organization name + timezone;
- founder full name + company email + password + confirm password;
- terms/privacy acknowledgement;
- “Already have a workspace? Sign in” → `/login`;
- submit → verification-pending screen, never directly bypassing email verification.

### 10.4.7 Invitation / activation acceptance
New route family:
- `/invite/[token]`
- `/activate/set-password`
- `/auth/mfa/setup`
- `/auth/mfa/challenge`
- `/auth/recovery-codes`
- `/welcome`

Invite screen clearly shows the inviting organization and intended role. The invitee sets their own password. Invitees complete MFA enrollment before dashboard access only when the organization's MFA policy requires it for them (8.3). Expired/used/revoked tokens get a polished safe state with a resend/contact-admin path; errors never leak unrelated tenant data.

### 10.4.8 Dashboard acceptance
The production dashboard follows the supplied end-to-end dashboard reference:
- fixed dark navy sidebar desktop; responsive compact/mobile navigation;
- translucent sticky topbar;
- glass cards + layered ambient shadows;
- editorial serif page titles + precise geometric sans stats;
- tabular/monospaced timers and data counters;
- gradient charts and live pulse indicators;
- role-specific navigation and content.

**Employee home:** personal workday timer/check-in/out, today/week snapshot, own pending-request tagline, calendar preview.

**Admin/Super Admin home:** no personal timer on console home; org-wide hours, live-attendance summary, actionable pending-approval tagline, calendar preview. Their personal check-in/out remains under **My Attendance**.

The prototype's “View as Employee” control is a **design/demo device only**. Production users never impersonate/switch role from a cosmetic toggle. Server-authorized roles determine navigation. Any real impersonation feature is out of scope unless separately designed/audited.

### 10.4.9 Required UX states
Every major page must define: loading skeleton, empty state, field validation, server error, offline/reconnecting where relevant, success confirmation, disabled/permission state, expired-session state and responsive behavior. Toasts never replace durable error text for failed forms. Destructive actions use confirmation and step-up auth where specified.

---

## 11. Org Settings (all data-driven, all audited, no hardcoding)

| Key | Default | Notes |
|---|---|---|
| `timezone` | required at setup | IANA, e.g. `Asia/Kolkata`; drives every day boundary |
| `weekly_off_days` | SAT, SUN | editable |
| `week_start_day` | MON | for weekly reports |
| `expected_daily_hours` | 8 | informational, not enforced |
| `present_min_minutes` | 30 | `[confirm]` |
| `session_warning_hours` / `session_max_hours` | 12 / 20 | max can be `null` (off) |
| `correction_window_days` | 30 | |
| `agent_mode` | ENCOURAGED | DISABLED / ENCOURAGED / REQUIRED |
| `comp_off_min_hours` / `comp_off_on_holidays` | 4 / true | `[confirm]` |
| `comp_off_expiry_days` | 90 | v2's 3-month expiry, `[confirm]` |
| `leave_backdate_window_days` / `leave_min_notice_days` | 7 / 0 | |
| `approval_reminder_days` / `approval_escalation_days` | 2 / 4 | |
| `email_approval_token_hours` / `email_approval_requires_login` | 72 / false | |
| `org_default_approver_id`, `backup_approver_id` | required | backup used for Super Admin requests |
| `sound_enabled` | true | |
| `mfa_policy` | DISABLED | DISABLED / OPTIONAL / REQUIRED_FOR_ADMINS / REQUIRED_FOR_SELECTED_USERS / REQUIRED_FOR_ALL; security setting, Super Admin only (8.3) |
| `refresh_sliding_days` / `refresh_absolute_days` | 30 / 90 | security setting |
| `presence_grace_minutes` | 10 | minimum 10 |

---

## 12. Data Model

**Org & people**
- `organization`: name, settings (Section 11), leave-policy version
- `department`: id, org_id, name (unique per org), description (nullable), created_by, created_at
- `employee`: id, org_id, department_id (nullable, single membership), employee_code, name, email, password_hash, status (INVITED / ACTIVE / DEACTIVATED / DELETED_TOMBSTONE), role, default_approver_id, mfa_enabled, **join_date, exit_date**, **welcome_seen_at** (nullable timestamp, 10.2)
- `calendar_event`: id, org_id, date, end_date (nullable, for multi-day entries), start_time / end_time (nullable), type (HOLIDAY / EVENT), name, description (nullable), location (nullable), mandatory (bool, `HOLIDAY` only), created_by, updated_by, created_at, updated_at — **replaces v4's `holiday` table**; only `type = HOLIDAY` rows feed `attendance_day`/leave-day math (4.3, 6.6)

**Attendance**
- `attendance_session`: id, employee_id, work_date, check_in_at, check_out_at (null while open), close_reason (MANUAL / SYSTEM_SHUTDOWN / SYSTEM_SHUTDOWN_ESTIMATED / DAY_SPLIT / SYSTEM_MAX_DURATION / DEACTIVATION / ADMIN_EDIT / CORRECTION_APPROVED), close_note, check_in_source, chain_root_id, continued_from_session_id, bound_device_ids, flags, time_adjusted (bool), idempotency_key, version, created_at. Constraints: one open per employee; no overlap; `check_out_at > check_in_at`
- `attendance_day`: employee_id, work_date, total_seconds, first_in, last_out, session_count, day_type, flags, computed_at
- `attendance_correction_request`: id, employee_id, session_id (nullable), proposed_check_in, proposed_check_out, reason, approval_request_id, created_at
- `month_lock`: org_id, month, locked_by, locked_at, unlocked_by, unlocked_at

**Devices / agent**
- `device`: id, employee_id, label, os, agent_version, public_key, paired_at, last_seen_at, boot_id, revoked_at
- `device_event`: id, device_id, seq, type (SHUTDOWN / LOGOFF / UNCLEAN_SHUTDOWN / AGENT_START), occurred_at, received_at, signature_valid, processed_session_id. Unique `(device_id, seq)`

**Leave**
- `leave_type`: id, org_id, name, accrual_amount, accrual_period, carry_forward, expiry_days, allow_negative, requires_document, policy_version, effective_from
- `leave_ledger`: id, employee_id, leave_type_id, entry_type, amount (decimal), effective_date, ref_type, ref_id, created_by, note
- `leave_balance`: employee_id, leave_type_id, balance (cache), period
- `leave_request`: id, employee_id, leave_type_id, start_date, end_date, duration, approval_request_id, status, created_at, decided_at
- `comp_off_credit`: id, employee_id, earned_date, expiry_date, used, used_in_request_id, source_day

**Approvals**
- `approval_request`: id, type, subject_id, requester_id, approver_id, status, version, escalation_level, decided_by, decided_at, decision_comment
- `approval_token`: id, approval_request_id, approver_id, token_hash, expires_at, used_at, used_ip, used_user_agent

**Security & comms**
- `audit_log` (append-only; DB role cannot UPDATE/DELETE): id, actor_id, action, target_type, target_id, timestamp, ip, details (before/after)
- `refresh_token`: id, employee_id, token_hash, family_id, created_at, expires_at, absolute_expires_at, revoked, device/user-agent label
- `login_attempt`: id, email, ip, success, timestamp
- `mfa_recovery_code`: employee_id, code_hash, used_at
- `notification`: id, employee_id, type, payload, read, created_at
- `notification_preference`: employee_id, type, email, in_app
- `email_outbox` / `email_send_log`: id, recipient, type, payload, status, attempts, next_attempt_at, provider_message_id, error, last_attempt_at
- `email_suppression`: email, reason, since

---


### 12.1 v9 tenant/auth additions
At minimum, extend the schema with:
- `organization(id, name, login_key_normalized UNIQUE, timezone, status, onboarding_completed_at, created_at, updated_at, ...)`
- Employee/Auth identity includes `organization_id NOT NULL`, `role`, `status`, `email_normalized`, verification timestamps and activation state.
- Uniqueness for employee email is tenant-scoped unless product policy later requires global uniqueness: `UNIQUE(organization_id, email_normalized)`.
- Invitation includes `organization_id`, target email, intended role, token hash, expiry, consumed/revoked timestamps, inviter id.
- Verification/reset/MFA/session records are tenant/account bound.
- Tenant-owned tables use tenant-safe foreign keys/constraints. Where practical use composite uniqueness/foreign keys including `organization_id` to make accidental cross-tenant references invalid at the database layer.
- Cache keys begin with `org:{organizationId}:...`; SSE channels/topics are tenant-scoped; export/background-job payloads carry tenant id explicitly.
- Audit rows include `organization_id`, actor id, action, target type/id, correlation id and immutable before/after metadata as applicable.
- Database tenant-defense policy/RLS is exercised in integration tests, not merely documented.

## 13.0 v9 bootstrap/auth endpoints

Public/non-authenticated:
- `POST /api/v1/public/organizations/register` — atomically create organization + founding Super Admin in pending-verification state.
- `POST /api/v1/public/organizations/verify-email` — consume founder verification token.
- `POST /api/v1/public/organizations/resend-verification` — non-enumerating, rate-limited.
- `POST /api/v1/auth/login` — Organization + email + password; no role input.
- `POST /api/v1/auth/mfa/challenge`
- `POST /api/v1/auth/forgot-password` — Organization + email; always-same public response.
- `POST /api/v1/auth/reset-password`
- `GET /api/v1/public/invitations/{token}/preview` — returns only safe invitation context for that token.
- `POST /api/v1/public/invitations/{token}/accept` — set invitee password; cannot change org/role.

Authenticated:
- `POST /api/v1/me/mfa/enroll`, `POST /api/v1/me/mfa/confirm`, recovery-code acknowledgement.
- `GET /api/v1/me` includes organization display data, role, firstName, onboarding state.
- `PATCH /api/v1/organization/onboarding` / completion endpoint for founder setup.
- `POST /api/v1/admin/employees/invite` — Admin/Super Admin, tenant-local Employee invitation.
- `POST /api/v1/super-admin/admins/invite` — Super Admin direct Admin invitation.
- `POST /api/v1/super-admin/employees/{id}/promote-admin` — existing Employee promotion; step-up.
- `POST /api/v1/admin/employees/{id}/deactivate` — role-authorized, tenant-local; revokes access.
- invitation resend/revoke endpoints are tenant-local and permission checked.

**Tenant contract for every authenticated endpoint:** ignore/reject client attempts to select another `organization_id`; tenant scope comes from the authenticated principal. Cross-tenant object IDs resolve as not-found within the caller's tenant.

## 13. API Surface (v1, all under `/api/v1`)

Standards: **pagination + sorting on every list endpoint (13.1)**, **request validation on every write endpoint (13.2)**, RFC 7807-style error body with correlation id, `Idempotency-Key` on state-changing POSTs, ISO-8601 UTC instants, OpenAPI for everything.

| Area | Endpoints |
|---|---|
| Time | `GET /time` |
| Profile | `GET /me` (own id, name, **firstName** derived per 10.3, email, department, role, status, joinDate, welcomeSeenAt), `POST /me/welcome/ack` (sets `welcome_seen_at`, 10.2) |
| Attendance | `GET /attendance/today`, `POST /attendance/check-in`, `POST /attendance/check-out` |
| Employee reports | `GET /me/attendance/daily?date=`, `GET /me/attendance/weekly?weekOf=`, `GET /me/attendance/monthly?month=YYYY-MM`, `GET /me/attendance/months`, `GET /me/attendance/monthly/export?month=&format=csv\|pdf` |
| Corrections | `POST /attendance/corrections`, `GET /me/attendance/corrections`, `DELETE /attendance/corrections/{id}` (pending only) |
| Devices / agent | `POST /me/devices/pairing-code`, `POST /agent/devices/pair`, `POST /agent/events` (signed), `POST /agent/heartbeat`, `GET /me/devices`, `DELETE /me/devices/{id}` |
| Approvals | `GET /approvals/pending?page=&size=&sort=`, `POST /approvals/{id}/decision {decision, comment, version}`, `GET /approvals/email/{token}` (read-only), `POST /approvals/email/{token}/decision` |
| Leave | `GET /leave/types`, `GET /me/leave/balances`, `GET /me/leave/ledger?page=&size=&sort=`, `POST /leave/requests`, `PATCH`/`DELETE /leave/requests/{id}` (pending), `POST /leave/requests/{id}/cancel` (approved) |
| Admin — employees | **`GET /admin/employees?page=&size=&sort=&search=&department=&role=&status=`** (3.5 directory: paginated, sortable, searchable, filterable), `GET /admin/employees/{id}`, `POST /admin/employees`, `PATCH /admin/employees/{id}`, `POST /admin/employees/{id}/deactivate`\|`/reactivate`, `POST /admin/employees/import`, `GET /admin/employees/{id}/attendance/monthly`, `PATCH /admin/attendance/sessions/{id}` |
| Admin — settings | Org settings CRUD, `POST /admin/months/{month}/lock`, `DELETE /admin/months/{month}/lock` |
| Departments | `GET /admin/departments?page=&size=&sort=`, `POST /admin/departments`, `PATCH/DELETE /admin/departments/{id}`, `POST /admin/departments/{id}/members` (add existing employees, body: employee ids), `DELETE /admin/departments/{id}/members/{employeeId}`, `GET /admin/departments/{id}` (full member details, paginated, Admin/Super Admin); `GET /me/department/roster?page=&size=` (own department, names only, id taken from token, Employee) |
| Calendar | `GET /calendar?from=&to=&page=&size=&sort=` (all roles, read-only, full details); `POST/PATCH/DELETE /admin/calendar/{id}` (Admin/Super Admin only) |
| Live dashboard | `GET /admin/attendance/live?department=&bucket=&page=&size=&sort=` (today's buckets, 5.5; not cached, D15), `GET /admin/attendance/live/stream` (SSE) |
| Reports | `GET /admin/reports/{attendance-summary\|leave-ledger\|comp-off\|corrections}?page=&size=&sort=&department=&from=&to=` with `?format=csv\|xlsx` for the full unpaginated export |
| Notifications | `GET /notifications?page=&size=&sort=`, `PATCH /notifications/{id}/read`, `GET /notifications/stream` (SSE), `GET/PUT /me/notification-preferences` |
| Security | `GET /me/sessions`, `DELETE /me/sessions/{id}`, `GET /admin/security/dashboard`, `GET /admin/audit-log?page=&size=&sort=&actor=&action=&from=&to=` |

### 13.1 Pagination & sorting (applies to every list endpoint above)
- **Request:** `page` (0-based, default 0), `size` (default 20, max 100 — larger asks get the CSV/Excel export instead, 5.3), `sort=field,direction` (repeatable for multi-column sort, e.g. `sort=name,asc&sort=joinDate,desc`). Unknown or non-sortable fields return a 400 with the allowed field list, not a silent ignore.
- **Response envelope**, identical shape everywhere: `{ items: [...], page, size, totalElements, totalPages }`. No endpoint returns a bare array.
- Screen-level defaults: Employee Directory (3.5) sorts by name ascending; audit log and notifications sort by timestamp descending; everything else states its own default in its own section.
- The one exception is the export formats (`?format=csv|pdf|xlsx`), which are intentionally unpaginated (streamed, 5.3) since the point is a complete file.

### 13.2 Request validation
- Every request DTO is annotated with Jakarta Bean Validation (`@NotBlank`, `@Email`, `@Past`/`@Future`, `@Size`, `@Pattern`, etc.); validation runs before the controller method body, so handlers never see a malformed payload.
- **Cross-field and business-rule validation** (things annotations can't express) lives in custom validators or the service layer: email uniqueness within an org, department id must exist and belong to the caller's org, leave date ranges must be start ≤ end, correction proposed times must not overlap other sessions (4.7), CSV row rules (3.4).
- All validation failures — annotation-level and custom — surface through the **same RFC 7807 error body** as every other error, with a `fieldErrors` array (`field`, `message`) so the frontend can highlight the exact field. No endpoint invents its own error shape.
- Enforced from **B0** (global `@ControllerAdvice` exception handler) so every phase from B2 onward gets consistent validation for free.

### 13.3 Caching strategy (D15)
- **Cached** (Spring Cache abstraction, Redis-backed, cache-aside): org settings (11), `leave_type` list (6.1), department list for dropdowns (3.1), calendar entries for a given month range (6.6). TTL 5–15 minutes as a safety net, **explicitly evicted** (`@CacheEvict`) the moment Admin writes to any of these — so a cache hit is never more than a few minutes stale even if eviction is ever missed, and normally it's instant.
- **Never cached:** open attendance sessions, `attendance_day` rollups, leave balances/ledger, the live dashboard (5.5), anything mid-approval-decision (7.4's optimistic locking already assumes a fresh read). These change too often, or a stale read would be actively wrong (double-approval, wrong clock state), so they always hit the database.
- Cache keys are namespaced per org (`org:{orgId}:leaveTypes`, etc.) so a multi-tenant future (currently out of scope, Section 1) doesn't leak between orgs by accident.

---

## 14. Non-Functional Requirements

### 14.1 Logging
- **Structured JSON logs** (Logback + a JSON encoder), one line per event — never multi-line stack-trace soup in production output.
- **Correlation id** generated at the edge (or propagated from an inbound header) and an **actor id** (employee id from the token, or `SYSTEM`/job name for scheduled jobs) injected via MDC on every request/job, so any log line can be traced back to the request and the person who caused it — this is the same correlation id already used in the RFC 7807 error body (13).
- **Levels are environment-driven**, not hardcoded: `DEBUG` locally, `INFO` in staging/production for business events (check-in/out, approvals, admin actions), `WARN` for retried/degraded operations (email retry, job catch-up), `ERROR` for anything that needs a human. No `DEBUG` or verbose SQL logging ships to production by default.
- **No PII in logs, ever** — this is the same rule as Sentry scrubbing below, applied at the source: log the employee id, never the email/name/phone in free-text log messages.
- A lightweight request-logging filter records method, path, status, duration and correlation id for every API call (excluding health checks) — this is what makes p95 numbers (14.3) and slow-endpoint investigation possible without re-instrumenting later.

### 14.2 Observability
- Sentry with **PII scrubbing** (no emails/tokens in events), Actuator metrics, alerts on job failure, email-failure rate, login-failure spikes, error rate. Logs (14.1) and Sentry are complementary: logs for "what happened, in order", Sentry for "what broke, with a stack trace and user impact".

### 14.3 Reliability & performance
- **Reliability:** open sessions survive deploys/restarts because state is in the DB. Scheduled jobs are idempotent, ShedLock-guarded, and catch up after downtime. Job failures alert (not silent).
- **Performance targets:** reports p95 under 300 ms via `attendance_day`; check-in/out p95 under 200 ms; SSE handles the org's concurrent users with headroom; cached lookups (13.3) resolve without a DB round-trip on a cache hit.

### 14.4 Backup, retention, accessibility, testing
- **Backup & DR:** encrypted backups, point-in-time recovery, proposed RPO ≤ 15 min and RTO ≤ 4 h `[confirm]`, **restore drill before go-live and quarterly**.
- **Data retention:** written policy before go-live for deactivated-employee PII, attendance/leave history, audit log and device events; the system supports configurable retention and anonymization jobs. Employees can export their own data. Policy content to be agreed with HR/legal (DPDP-style obligations).
- **Accessibility & UX:** WCAG 2.1 AA baseline, keyboard-navigable, responsive/mobile web, reduced-motion support, clear empty/error/loading states.
- **Testing:** every phase ships with unit + integration tests (Testcontainers); negative authorization tests per endpoint; time-based logic tested with an injectable clock (day split, DST-safe durations, org timezone); Playwright covers check-in → tab close → reopen → still running → manual check-out.

---

## 15. Security Requirements (non-negotiable, consolidated)

1. Server-side timestamps only for attendance; the sole client-influenced times are bounded offline check-out (4.6) and signed agent events (4.4), both flagged.
2. Authorization in the service layer, IDOR-proof self endpoints, Admin cannot touch Super Admin, no self-approval.
3. Passwords hashed with Argon2id/bcrypt; breached-password check; lockout/backoff; generic auth errors.
4. Access token in memory; rotating refresh cookie with reuse detection, sliding + **absolute** lifetime; same-site deployment; CSRF protection.
5. MFA (TOTP with recovery codes) available to every organization and enforced according to the organization's MFA policy (8.3); step-up auth for destructive actions.
6. Approval tokens single-use, hashed, bound to request+approver, expiring, GET-safe confirmation page.
7. Agent: keys in OS keystore, signed events, replay protection, signed installers/updates, revocable devices.
8. CSV/Excel formula-injection defence on import and export; row/size limits on uploads.
9. Append-only audit log covering logins, privileged actions, role changes, exports, attendance edits, corrections, month lock/unlock, device pairing, settings changes.
10. Rate limiting on login, reset, approvals, check-in/out, exports, agent endpoints.
11. Security headers and strict CSP on the frontend, locked-down CORS, TLS everywhere, DB encryption at rest.
12. Secrets never in repos (gitleaks pre-commit + CI), signing-key rotation, dependency + SAST scanning, SBOM.
13. PII scrubbed from logs and Sentry; least-privilege DB roles (audit table write-only for the app).
14. SPF/DKIM/DMARC, bounce handling, clear sender identity.
15. Independent security review and penetration test before production.

---

### 15.1 Tenant-isolation security gates (v9)
- Every repository/service method handling tenant-owned data has an explicit tenant scope or operates under a verified tenant context.
- No unrestricted `findById(id)` for tenant-owned aggregates in request paths; use tenant-qualified lookup or DB policy.
- Authorization tests include malicious foreign-tenant UUID substitution for read/update/delete/export/approval/calendar/report endpoints.
- Login/forgot-password/resend-verification timing and wording are tested for organization/account enumeration resistance.
- SSE/WebSocket (if introduced), Redis, caches, scheduled jobs and email payload builders are tested for tenant leakage.
- Admin/Super Admin permissions are **organization-local**, never platform-global.
- Platform operations, if ever needed, require a separate future control plane/identity model; they are not implemented by granting a tenant Super Admin cross-tenant access.
- Logs/Sentry must avoid leaking another tenant's PII into a tenant-visible surface. Operational logs may carry internal organization id for support correlation but are never exposed cross-tenant.

## 16. Repositories & Git Workflow

### 16.1 Repositories
| Repo | Purpose |
|---|---|
| `peoplehub-backend` | Spring Boot API, Flyway migrations, OpenAPI |
| `peoplehub-frontend` | Next.js app, generated API client |
| `peoplehub-agent` | Desktop agent (Tauri), installers, update channel |
| `peoplehub-infra` | docker-compose, CI/CD workflows, environment templates, runbooks |
| `peoplehub-docs` | This document, ADRs, security notes, retention policy |

### 16.2 Rules for every repo (no `main`/`master` pushes, ever)
- **Nobody commits directly to `main`.** Every change, including docs and hotfixes, is a branch → pull request → merge.
- **Branch naming:** `<type>/<phase>-<n>-<slug>` where type ∈ `feature | fix | chore | docs | hotfix`, e.g. `feature/b4-2-check-in-out-idempotent`. One branch per small change; aim for PRs a reviewer can read in one sitting.
- **Branch protection on `main` (set in GitHub/GitLab settings; I cannot apply these for you):** require PR; at least 1 approving review (solo developer: required CI checks plus the PR self-review checklist, add a reviewer as soon as there is one); required status checks (build, unit + integration tests, lint, SAST, dependency scan, secret scan, OpenAPI breaking-change check); branch must be up to date before merge; squash merge / linear history; block force-push and deletion; **no bypass, admins included**; signed commits recommended; CODEOWNERS on auth, migrations and security paths; auto-delete merged branches.
- **Commits:** Conventional Commits (`feat:`, `fix:`, `chore:`...).
- **PR template checklist:** tests added; negative-auth test; audit-log entry; migration reviewed; OpenAPI updated; docs updated; security notes; screenshots for UI.
- **Migrations:** forward-only; never edit an applied migration; destructive changes use expand/contract.
- **Releases:** SemVer tags per repo. `main` auto-deploys to **staging**; **production deploys from a tag with a manual approval**. Hotfix: `hotfix/*` branched from the tag → PR to `main` → new tag.
- **Contract:** backend publishes OpenAPI per tag; frontend and agent regenerate clients from it; CI fails on breaking changes.

### 16.3 Definition of Done (every phase)
Merged via PR with green CI; tests (incl. authorization and time-based edge cases) pass; OpenAPI and docs updated; migrations reviewed; audit and notifications wired; security checklist items for that phase ticked; demo-able through API tests (backend) or E2E (frontend).

### 16.4 Containerization & environments (D16)
- **`peoplehub-backend` Dockerfile:** multi-stage build — a build stage compiles with the JDK, a slim runtime stage (JRE-only base image) copies just the built jar; runs as a **non-root user**; exposes the Actuator health endpoint for container health checks; image tagged and pushed to a registry per SemVer tag (16.2).
- **`peoplehub-frontend`:** builds to static/Node output per Vercel's normal flow for staging/production (Section 2); a Dockerfile is still provided for local parity and any non-Vercel environment.
- **`peoplehub-infra/docker-compose.yml`** brings up the whole stack for local dev and CI: Postgres, Redis, the backend container, the frontend container, and a dev-only mail catcher (e.g. Mailhog) so outbox emails (9.2) can be inspected without a real provider. This is the **same compose file CI uses** for Testcontainers-backed integration tests (14.4) — no separate "CI-only" setup to drift from what a developer runs locally.
- **Environment configuration, `.env`-driven everywhere:** `peoplehub-infra` ships a checked-in `.env.example` (every variable named, no real values) and each developer keeps a local, git-ignored `.env` (DB URL, Redis URL, JWT signing keys, email provider keys, `APP_NAME`). This is exactly what the **EnvFile** IntelliJ plugin (21) is for locally — pointing IntelliJ's run configuration at `.env` so secrets never get hand-typed into a run config or committed. Staging/production load the equivalent variables from the host/orchestrator's secret store, never from a checked-in file.
- **Health & graceful shutdown:** Actuator `/actuator/health` (liveness) and a readiness check that also verifies DB/Redis connectivity, used by the container orchestrator; Spring's graceful shutdown is enabled so an in-flight request or scheduled job finishes before a container stops (relevant given ShedLock jobs and long-running attendance sessions in Section 4).
- **Resource limits** (CPU/memory) are set on every container from the start, even locally, so a runaway query or leak is caught in dev, not discovered in production.

---

## 16.5 Current Repository Checkpoint (v9 adoption)

This section records where implementation actually is when v9 is adopted. It is not a request to redo merged work.

| Sub-branch | State at v9 checkpoint | Required next action |
|---|---|---|
| `b0-1` | Merged baseline | Preserve |
| `b0-2` | Merged baseline | Preserve |
| `b0-3` | Merged baseline | Preserve |
| `b0-4` | Implementation merged (PR #4) | Manually merge the separate docs/status PR if still open; do not rewrite implementation |
| `b0-5` | Implemented + verified locally; not committed | Apply/review 5s second shutdown wait, document combined worst-case shutdown, note orchestrator follow-up for b0-7, inspect full diff, then commit/PR |
| `b0-6` | Not started | Implement append-only audit log after b0-5 merges |
| `b0-7` | Not started | Implement docker-compose; include orchestrator shutdown grace/budget consistent with b0-4/b0-5 worst case |

v9's new organization bootstrap/tenant functionality begins primarily in **B2**, after B0/B1 prerequisites. If B0-6 audit schema needs `organization_id`, design it forward-compatibly without implementing B2 prematurely.

---

## 17. Build Phases

Order: **Backend fully complete and tagged `v1.0.0` (B0-B14) → Frontend (F0-F10) → Agent (A1-A4) → Release (R1-R4).** Exception: the **agent spike A0 runs early**, because if an OS cannot reliably report shutdown, decision D2/D3 needs revisiting. Each phase merges before the next starts. Branches below are separate PRs.

### Backend track: `peoplehub-backend`

| Phase | Scope | Branches (each its own PR) | Exit criteria |
|---|---|---|---|
| **B0 Foundation** | Skeleton, Flyway, Testcontainers, Redis, ShedLock, `docker-compose.yml` + Dockerfile (16.4), structured JSON logging + MDC correlation id (14.1), global validation `@ControllerAdvice` (13.2), API standards + OpenAPI (incl. pagination/sort envelope, 13.1), error format, Sentry, health, injectable clock, **append-only audit log**, CI | `b0-1-skeleton-ci`, `b0-2-db-flyway-testcontainers`, `b0-3-api-standards-pagination-openapi`, `b0-4-logging-validation-observability`, `b0-5-scheduler-shedlock`, `b0-6-audit-log-append-only`, `b0-7-docker-compose` | CI green; sample migration; OpenAPI published; audit table rejects UPDATE/DELETE; `docker compose up` boots the full stack locally; a deliberately bad request returns a field-level RFC 7807 error |
| **B1 Email & notification platform** *(moved earlier: invites need it)* | EmailService, outbox, retry/backoff, send log, templates, in-app notifications + SSE, bounce/suppression | `b1-1-email-service-outbox`, `b1-2-templates-retry-log`, `b1-3-inapp-sse`, `b1-4-bounce-suppression` | Invite mail sent through dev SMTP with retry; failure visible to Admin; SSE test passes |
| **B2 Org, tenant isolation, employees & auth** | Multi-org tenant schema + DB isolation, organization registration/bootstrap, founder verification, founding Super Admin, onboarding state, tenant-scoped employee schema, Organization+email+password login, JWT + rotating refresh (absolute cap), Employee/Admin invitations, activation, direct Admin invite + promotion, deactivation/revocation, forgot/change password, password policy + breach check, lockout, sessions list/revoke, TOTP + recovery + step-up | `b2-1-org-tenant-employee-schema`, `b2-2-org-bootstrap-founder-verification`, `b2-3-login-jwt-refresh-tenant-context`, `b2-4-invite-activation-admin-invite`, `b2-5-password-policy-lockout-reset`, `b2-6-sessions-deactivation-revoke`, `b2-7-mfa-stepup-onboarding`, `b2-8-tenant-isolation-security-tests` | Full founder→verification→onboarding (MFA policy per 8.3)→dashboard-ready auth flow and invite→activation flow via API tests; token reuse revokes family; cross-tenant UUID attacks fail; org/account enumeration tests pass; Organization A cannot discover/access Organization B |
| **B3 Roles, departments, calendar & admin lifecycle** | RBAC + authorization matrix, departments (CRUD, add/remove members, single-membership, own-department name-only roster endpoint), employee CRUD/deactivate with department assignment on create/edit, **paginated/sortable/searchable employee directory endpoint (3.5)**, admin lifecycle (tombstone, last-Super-Admin guard), bulk CSV import, org calendar (`calendar_event`: holidays + events, Admin CRUD, employee read-only), org settings API (cached, 13.3) | `b3-1-rbac-authz-matrix`, `b3-2-departments-membership-roster`, `b3-3-employee-crud-directory`, `b3-4-admin-lifecycle-tombstone`, `b3-5-bulk-import`, `b3-6-calendar-org-settings-cache` | Every endpoint has a negative authz test; department roster endpoint never accepts a caller-supplied department id; directory search/filter/sort/paginate all verified together; import report per row; last Super Admin cannot be removed |
| **B4 Attendance core** | Session schema + constraints, check-in/out idempotent, `today` + `time`, day-split job, safety cap + warnings, `attendance_day` rollup, admin view/edit | `b4-1-session-schema`, `b4-2-check-in-out-idempotent`, `b4-3-today-time`, `b4-4-day-split-safety-cap`, `b4-5-day-rollup`, `b4-6-admin-view-edit` | Session survives simulated restart; two parallel check-ins yield one session; midnight split correct across org timezone; job catch-up tested |
| **B5 Device pairing & shutdown events** | Pairing codes, device registry, signed event ingestion, replay protection, binding + last-device-down logic, unclean-shutdown estimate, device management | `b5-1-device-pairing`, `b5-2-signed-events-shutdown-close`, `b5-3-unclean-shutdown-estimate`, `b5-4-device-management` | Simulated agent events close/keep sessions per 4.4 rules; forged/replayed events rejected |
| **B6 Attendance reports API** | Employee daily, weekly, monthly, months list, safe CSV/PDF export | `b6-1-daily`, `b6-2-weekly`, `b6-3-monthly-months`, `b6-4-export-csv-pdf` | Daily = weekly = monthly totals verified; previous months back to join date; shutdown/auto-close notes present in payloads |
| **B7 Approval engine** | Generic request/decision service, approver resolution + fallback, reminders/escalation, safe email confirm-page tokens, optimistic locking | `b7-1-approval-core`, `b7-2-approver-fallback-reminders`, `b7-3-email-confirm-tokens`, `b7-4-console-decision-locking` | No self-approval; scanner-style GET changes nothing; double decision cannot double-apply |
| **B8 Attendance corrections** | Request model, window + month-lock checks, apply on approval, rollup + comp-off re-evaluation | `b8-1-correction-request`, `b8-2-window-monthlock-validation`, `b8-3-apply-on-approval` | Correction changes reports; overlap and window rules enforced |
| **B9 Leave core** | Types + ledger, accrual job with pro-rata, apply/validate/overlap, edit/cancel/restore, approval hook | `b9-1-types-ledger`, `b9-2-accrual-prorata-job`, `b9-3-apply-validate-overlap`, `b9-4-edit-cancel-restore`, `b9-5-approval-balance-hook` | Ledger always equals balance; cancel restores; idempotent deductions |
| **B10 Comp-off** | Credit at day finalization, expiry job + reminder, apply comp-off | `b10-1-credit-on-finalize`, `b10-2-expiry-reminder-job`, `b10-3-apply-comp-off` | A credit is used exactly once; reversal via corrections works |
| **B11 Admin reports & month lock** | Attendance summary, leave/comp-off/corrections reports, safe exports + audit, month lock/unlock | `b11-1-attendance-summary`, `b11-2-leave-compoff-reports`, `b11-3-export-audit-injection-safe`, `b11-4-month-lock` | Exports audit-logged and formula-safe; locked month blocks edits |
| **B12 Presence** | Heartbeat, 10-minute grace, who's-online API, decoupled from auth/attendance | `b12-1-heartbeat-presence` | Presence never affects attendance (test) |
| **B13 Hardening, dashboard, retention, DR** | Rate limits, headers, CORS, security dashboard API, retention/anonymization jobs, backup/restore drill, dependency/SAST/secret scanning | `b13-1-rate-limits-headers`, `b13-2-security-dashboard`, `b13-3-retention-jobs`, `b13-4-backup-restore-drill` | Restore drill passed; scans clean |
| **B14 Backend QA & freeze** | Full integration, load and security tests, staging environment mirroring prod, API freeze | `b14-1-integration-load-tests`, `b14-2-security-tests`, `b14-3-staging-api-freeze` | Tag `v1.0.0`; OpenAPI frozen for frontend |

### Frontend track: `peoplehub-frontend` (starts after B14)

| Phase | Scope | Branches | Exit criteria |
|---|---|---|---|
| **F0 Foundation** | Next.js skeleton, design system, generated API client, auth shell **with a persistent "Hello, {firstName}" greeting sourced from `GET /me` (10.3)**, Sentry, theming, a11y baseline, **one reusable paginated/sortable table component consuming the standard 13.1 envelope** (built once, used by every list screen below) | `f0-1-skeleton-design-system`, `f0-2-api-client-auth-shell-greeting`, `f0-3-paginated-table-component` | Builds against staging API; the table component renders against a mock 13.1 envelope with page/sort controls working; greeting shows the correct `firstName` for a name with no space and a name with multiple given names |
| **F1 Auth screens** | Login (org + email + password), MFA, activation, **one-time post-activation welcome screen (10.2)**, forgot/reset, sessions page, silent refresh | `f1-1-login-mfa`, `f1-2-activate-reset-welcome`, `f1-3-sessions` | Refresh works; no token in storage; welcome screen shows exactly once per employee, never again after `POST /me/welcome/ack` |
| **F2 Clock widget** | Server-derived timer, cross-tab sync, rehydrate on reopen, offline intent, shutdown/auto-close banners, logout warning | `f2-1-clock-widget`, `f2-2-cross-tab-offline`, `f2-3-notices-banners` | E2E: check in → close tab → reopen → still counting → manual check-out |
| **F3 My Attendance, calendar & team** | Daily, weekly, monthly, month picker back to join date, heatmap, downloads, correction shortcut; read-only org calendar view; own-department roster widget (names only, hidden entirely if unassigned) | `f3-1-daily`, `f3-2-weekly`, `f3-3-monthly-picker`, `f3-4-downloads`, `f3-5-calendar-view`, `f3-6-department-roster` | Numbers match API; previous months work; employee cannot edit calendar entries or fetch another department's roster even by direct API call |
| **F4 Leave** | Apply (balance preview), my requests, edit/cancel, ledger, comp-off | `f4-1-apply`, `f4-2-requests-cancel`, `f4-3-balance-ledger-compoff` | Overlap and balance errors clearly shown |
| **F5 Approvals & corrections** | Approver inbox, correction form, email confirm page, digest links | `f5-1-inbox`, `f5-2-correction-form`, `f5-3-email-confirm-page` | Already-resolved page friendly; no state change on GET |
| **F6 Admin** | **Employee directory (3.5): search, department/role/status filters, sortable columns, pagination, reused table component (F0)**, with department picker on add/edit; bulk-import wizard with per-row report; department manager (create/rename/delete, add/remove members); org settings; calendar admin (date-picker create/edit/delete for holidays + events); admin attendance/leave views; month lock; live attendance dashboard (5.5) | `f6-1-employee-directory`, `f6-2-departments-members`, `f6-3-calendar-admin`, `f6-4-admin-attendance-leave-live-dashboard`, `f6-5-settings-month-lock` | Admin flows covered by E2E; directory search/filter/sort/paginate all verified; department member add/remove reflected instantly in employee's own roster view |
| **F7 Admin reports & security** | Report builder/export (paginated on-screen tables per 13.1, full unpaginated file for CSV/Excel/PDF download), security dashboard, audit-log viewer (paginated, filterable by actor/action/date), device management | `f7-1-reports`, `f7-2-security-dashboard-audit` | Super Admin-only screens gated; on-screen report tables paginate/sort, downloaded files contain the full range |
| **F8 Notifications** | Bell + SSE, preferences | `f8-1-bell-sse`, `f8-2-preferences` | Real-time delivery verified |
| **F9 Celebration & presence** | Clock-in animation/sound, long-day cheer, mute, who's-online widget | `f9-1-celebration`, `f9-2-presence-widget` | Reduced-motion respected |
| **F10 Polish & E2E** | Accessibility audit, responsive pass, error states, performance budget, Playwright critical paths | `f10-1-a11y-responsive`, `f10-2-e2e-performance` | All critical paths green |

### Agent track: `peoplehub-agent`

| Phase | Scope | Branches | Exit criteria |
|---|---|---|---|
| **A0 Spike (run early)** | Prove shutdown/logoff detection + boot identity on Windows (incl. Fast Startup), macOS, Linux; choose Tauri vs Electron; define supported OS list | `a0-1-shutdown-hook-spike` | Written findings; go/no-go per OS |
| **A1 Pairing & heartbeat** | Pairing code flow, keystore, signed heartbeat | `a1-1-pairing-keystore`, `a1-2-heartbeat` | Device appears in the web app |
| **A2 Events** | Shutdown/logoff capture, disk queue, boot-id / unclean-shutdown detection, retry | `a2-1-shutdown-events`, `a2-2-queue-unclean-detect` | End-to-end with backend B5: shutdown closes a session with the right label |
| **A3 UX & distribution** | Tray, "check in?" prompt after boot, event viewer, installers, code signing, auto-update, uninstall = revoke | `a3-1-tray-ux`, `a3-2-installers-signing-update` | Installable on supported OSes |
| **A4 Privacy & review** | Published data-collected statement, security review | `a4-1-privacy-statement` | Reviewed and signed off |

### Release track (`peoplehub-infra`)
R1 Staging that mirrors prod; R2 full E2E + independent security review/pen-test; R3 UAT with real HR users, SPF/DKIM/DMARC verified, backup restore verified; R4 production deploy from tag, smoke test, runbooks (incl. break-glass), monitoring/alerts live.

---

## 18. Boosters (optional, post-launch; do not change frozen scope)

| Booster | Value | Effort |
|---|---|---|
| **Smart nudges** (agent detects boot with no check-in; long-session and forgot-checkout prompts) | Fewer missed punches and corrections | Low |
| **Team calendar + coverage warning** ("2 of 5 in your team already on leave") | Better leave decisions; uses departments | Medium |
| **One-tap approvals** (mobile-friendly + PWA, daily digest) | Faster approvals | Low-Med |
| **Employee insights** (trends, streaks, target vs actual, month over month) | Makes reports engaging | Low-Med |
| **Admin analytics** (absence patterns, leave utilisation, comp-off liability, department trends) | Real management value | Medium |
| **Work-mode tag** at check-in (Office / Remote / Field) and on-duty type | Fits remote work; no geo-tracking needed | Low |
| **Half-day and short-leave** (already modeled as decimals) | Common real-world need | Medium |
| **Payroll-ready export** (fixed monthly format) | Saves manual work | Low |
| **Calendar sync** (ICS feed of holidays/approved leave) | Convenience | Low |
| **Slack/Teams approvals + webhooks/public API** | Meets people where they work | Medium |
| **Birthdays / anniversaries + announcements** | Extends the celebration layer | Low |
| **Directory / org chart with managers** | Enables manager-based approvals | Medium |
| **Sick-leave attachments** (with antivirus scan) | Policy compliance | Medium |

Suggested order: nudges → team calendar → one-tap approvals → insights → admin analytics.

---

## 19. Open Items (not blocking start)

- Exact backend hosting provider (decide at B0).
- Whether casual leave has a cap despite carry-forward; whether any of the 5 yearly extra holidays are optional/restricted.
- Confirm all `[confirm]` defaults: present threshold, comp-off minimum hours and expiry, refresh lifetimes, RPO/RTO.
- Data-retention periods for PII, attendance/leave history, audit log and device events (with HR/legal).
- Supported operating systems for the agent (decided by spike A0).
- Whether check-in should ever be IP/geo-restricted (currently out of scope; remote work assumed).
- Whether `agent_mode = REQUIRED` is wanted.
- Leader-election across tabs for presence heartbeat: only if it becomes a measurable load issue.
- Logo/branding: cosmetic, last.

---

## 20. Gap Register: v3 → v4 → v5 → v6 → v7 → v8 → v9

| # | Gap in v3 | Severity | Fix (section) |
|---|---|---|---|
| 1 | One attendance record per day cannot represent shutdown-then-recheck-in or midnight splits | High | Session model (4.2) |
| 2 | Browsers cannot detect shutdown; requirement unmeetable web-only | High | Desktop agent (4.4) |
| 3 | Midnight auto-checkout contradicts "counts until manual or shutdown" | High | Day split + safety cap (4.5) |
| 4 | Power loss/crash with no shutdown event | Medium | Estimated close on next boot (4.4) |
| 5 | Multi-device users: which shutdown ends the day | Medium | Device binding, last-device-down rule (4.4) |
| 6 | Client clock skew and offline check-out | Medium | Server offset, bounded offline intent (4.6) |
| 7 | Logout/token expiry vs attendance unclear | Medium | Rule R2 + logout warning (4.1, 4.6) |
| 8 | Scheduled jobs duplicate across instances or miss runs | Medium | ShedLock + catch-up (2, 14) |
| 9 | No employee self-service reports | High | My Attendance (5.1) |
| 10 | No correction window or month lock; history editable forever | Medium | 4.7, 5.4 |
| 11 | DST/timezone-safe duration handling unspecified | Low | `timestamptz` + instant math (4.2) |
| 12 | Behaviour when working on off-day/holiday/leave day; comp-off threshold and timing | Low | 4.3, 4.9 |
| 13 | Phase order bug: corrections (Ph4) depended on approval endpoint (Ph5) | High | Approval engine before corrections (B7 → B8) |
| 14 | Email GET links consumed by mail scanners | High | Confirmation page (7.3) |
| 15 | Self-approval by Admin/Super Admin | High | No self-approval + fallback chain (7.2) |
| 16 | No approver reassignment, reminders, escalation | Medium | 7.2 |
| 17 | Double-decision races | Medium | Optimistic locking (7.4) |
| 18 | Leave balance as a single number, no history | High | Ledger (6.2) |
| 19 | Cancelling approved leave undefined | Medium | 6.4 |
| 20 | No LOP/negative balance rules, pro-rata, policy versioning, backdating window | Medium | 6.1-6.3 |
| 21 | Vercel frontend + separate API domain breaks httpOnly refresh cookie | High | Same-site domains (D10, 8.1) |
| 22 | Forgot-password flow missing | High | 8.2 |
| 23 | Last Super Admin protection; Admin vs Super Admin boundaries | High | 3.2, 3.3 |
| 24 | "Delete Admin" conflicts with audit-log/FK integrity | High | Tombstone (D7, 3.3) |
| 25 | MFA optional for privileged roles; no recovery/step-up | Medium | 8.3 |
| 26 | CSV formula injection (import and export) | Medium | 3.4, 5.3 |
| 27 | JWT key rotation, in-memory access token, CSRF on refresh | Medium | 8.1 |
| 28 | Audit log not tamper-resistant | Medium | Append-only table (12, B0) |
| 29 | PII in logs/Sentry | Medium | Scrubbing (14) |
| 30 | Account/org enumeration via login errors | Medium | 8.2 |
| 31 | Email needed in Phase 1 but built in Phase 8 | High | Email platform is B1 |
| 32 | No outbox, bounces, suppression | Medium | 9.2 |
| 33 | No notification preferences | Low | 9.3 |
| 34 | No real-time in-app channel | Medium | SSE (2, 9.2) |
| 35 | No API standards (pagination, errors, OpenAPI, idempotency) | Medium | 13 |
| 36 | No backup RPO/RTO or restore drills | Medium | 14 |
| 37 | No observability/alerting on jobs and email | Medium | 14 |
| 38 | Accessibility and audio autoplay/reduced motion | Low | 10, 14 |
| 39 | Missing employee fields (join date, code, exit date) needed for reports/pro-rata | Medium | 12 |
| 40 | No branch/repo workflow; no agent repo; backend/frontend order not defined | High | 16, 17 |
| 41 | Departments were a filter label only; no membership management and no peer visibility between employees | High | Department CRUD, membership, name-only roster (3.1) |
| 42 | Holiday calendar had no general-purpose events, and it wasn't explicit that employees can only view it, never edit it | Medium | Org calendar (`calendar_event`, 6.6) |
| 43 | "Who is in" was one vague admin bullet with no defined states, no idle detection, and no live/real-time behaviour specified | Medium | Live Attendance Dashboard (5.5) |
| 44 | Admin employee list existed only implicitly as "employees CRUD"; no defined search, filter, sort or pagination | Medium | Employee Directory (3.5), pagination/sort standard (13.1) |
| 45 | Pagination mentioned once in passing ("cursor/page pagination"), never specified as a contract, never applied per-endpoint; no sorting standard at all | Medium | 13, 13.1 |
| 46 | No explicit request-validation framework, no structured-logging spec, no domain caching strategy, and "docker-compose" was named once with no Dockerfile, health checks, or `.env` handling — "production ready" had no checklist | High | Validation (13.2), Logging (14.1), Caching (13.3), Containerization (16.4), Production Readiness (22) |
| 47 | No welcome/first-login moment and no name-based greeting anywhere; if built ad hoc, every screen would likely re-implement its own "split the name" logic | Low | Welcome screen + `firstName` via `GET /me` (10.2–10.3, D17) |
| 48 | No role dashboard was specified at all — "the dashboard" was implied by 5.1/5.2/5.5 individually; nothing said what Admin vs. Employee actually lands on, and nothing stopped a personal timer from ending up on the Admin console home | Medium | Role Dashboards (5.6, D18) |
| 49 | Pending approvals were only visible by opening the Approvals queue; an Admin/Super Admin with items waiting had no glanceable signal on login, and an employee had no equivalent view of their own submitted requests | Medium | Dashboard pending-request taglines (5.6, 7.5, D19) |
| 50 | Directory/Live-Attendance search was named ("search by name") but never specified what happens on a match, and department was listed as a "filter" with no stated relationship to the search box — two screens could easily have implemented this differently | Low | Search & department-filter behavior (3.5, 5.5, D20) |

---


### v9 gap closures added by this revision
| # | Gap found after v8 | Severity | v9 closure |
|---|---|---|---|
| 51 | No way to create the first organization / first Super Admin | Critical | D23, 2.1.3, B2 bootstrap |
| 52 | “one deployed instance = one company” conflicts with public registration | Critical | D21/D22/D30 multi-org hard tenant isolation |
| 53 | Tenant could potentially infer/access another tenant if IDs leak | Critical | tenant-qualified repositories + DB defense + security tests |
| 54 | No direct new-Admin invitation | High | D25 + direct Admin invite endpoint/flow |
| 55 | Employee/Admin credential ownership not explicit | High | D24: invitee privately sets password |
| 56 | Deactivation login/session behavior incomplete | High | D26 + session/token/device revocation |
| 57 | Login role selection ambiguity | Medium | D27: same Organization+email+password for all, no role picker |
| 58 | Static prototypes could be mistaken for production stack | High | D28 + Section 10.4 visual contract |
| 59 | Auth/onboarding UI routes incomplete | High | F1 expanded with registration/verification/MFA/onboarding/invite activation |
| 60 | Current B0 repository state absent from master plan | High | 16.5 checkpoint; preserve merged work |
| 61 | No visual-regression acceptance criteria for approved UI | Medium | F10 screenshot/visual QA + 10.4 |
| 62 | Organization enumeration risk not fully defined | Critical | D22/D29 + generic/timing-safe login/reset/resend behavior |

---

## 21. IDE & Developer Tooling

You already have these six IntelliJ plugins installed; this section says where each one actually earns its keep in the plan above, so they're used deliberately rather than just sitting there.

| Plugin | Where it's used | Notes |
|---|---|---|
| **Lombok** | Every entity, DTO and value object from **B0 onward** (`@Data`, `@Builder`, `@Getter`/`@Setter` per the team's usual style) — the plugin itself does nothing but stop IntelliJ from flagging Lombok-generated methods as errors. | Doesn't replace the actual annotation-processor dependency in the build (`pom.xml`/`build.gradle`) — that has to be added regardless of the plugin. |
| **JPA Buddy** | Whenever an entity changes shape — new/changed columns, new tables — across **B2–B12** wherever `@Entity` classes are touched (employee, department, `calendar_event`, `attendance_session`, `leave_ledger`, etc.): use it to **draft** the Flyway migration and the entity-relationship diagram. | It drafts, it doesn't replace 16.2's rule that a human reviews every migration before merge and migrations are forward-only — treat its output as a first draft, not a commit. |
| **SonarQube for IDE (SonarLint)** | Turned on for the whole codebase from **B0**: it's the local, real-time companion to the SAST/dependency-scan CI gate already required in 16.2 — catch the null-pointer risk or the sloppy pattern in the editor, before it ever reaches a PR and the CI gate. | Doesn't replace the CI-side SAST check (16.2) — that stays a required, un-bypassable status check; SonarLint just means fewer findings show up there in the first place. |
| **GitToolBox** | Ambient, all the time, no phase-specific use: inline blame ("changed by X, 2 days ago") and ahead/behind-`main` indicators support the branch-per-change workflow in 16.2 (small PRs, keep-branch-up-to-date-before-merge) by making it obvious at a glance when a branch has drifted. | Purely a workflow aid — it enforces nothing and isn't a CI gate. |
| **OpenAPI (Swagger) Editor** | Whenever the OpenAPI contract is being hand-edited or reviewed (13's endpoint table, springdoc-openapi's generated spec) — from **B0's `b0-3-api-standards-pagination-openapi` branch onward, and every phase that adds endpoints**: live-preview how the Swagger UI will render before running the app, and before the frontend regenerates its client from it (2, "API contract"). | Complements, doesn't replace, the CI check that fails on breaking OpenAPI changes (16.2). |
| **EnvFile** | Local IntelliJ run configurations from **B0's `docker-compose`/`.env.example` setup (16.4) onward**: point the backend's run config at the developer's local `.env` so DB/Redis URLs, JWT signing keys and email-provider keys load automatically instead of being pasted into the run config (and risking an accidental commit). | Local convenience only — staging/production still load secrets from the host/orchestrator's secret store (16.4), never from a `.env` file. |

---

## 22. Production Readiness Checklist

A consolidated go/no-go list for launch, pulling together items that already exist scattered across this document — nothing here is new scope, it's what "production ready" concretely means for everything above. Treat it as the gate for **R3/R4** (Release track, 17).

**Containerization & environments**
- [ ] Backend Dockerfile is a non-root, multi-stage build; image builds and runs from a clean checkout (16.4).
- [ ] `docker-compose.yml` boots the full stack (Postgres, Redis, backend, frontend, mail catcher) locally and in CI (16.4).
- [ ] Every secret/config comes from `.env` locally and from the host/orchestrator's secret store in staging/production — nothing hardcoded, nothing committed (16.4, 8).
- [ ] Health/readiness endpoints wired to the orchestrator; graceful shutdown verified under load (16.4).

**Correctness & data**
- [ ] Every write endpoint validated (13.2) with field-level RFC 7807 errors; every list endpoint paginated and sortable with the standard envelope (13.1).
- [ ] Migrations are forward-only, reviewed, and none have been hand-edited after applying (16.2).
- [ ] Month lock, correction windows, and last-Super-Admin protections all covered by tests (3.3, 5.4, 4.7).
- [ ] Dashboard pending-request tagline (5.6, 7.5) verified end-to-end: appears at `n > 0`, links to the correct filtered queue, updates live on decision, and fully disappears at `n = 0` — tested for both the Admin/Super Admin and Employee variants.
- [ ] Directory and Live Attendance search/department-filter behavior (3.5, 5.5, D20) verified: a name match opens the right employee's detail card; a department selection narrows the list and combines correctly with an active name search.

**Performance & caching**
- [ ] Cache hit/eviction behaviour verified for org settings, leave types, department list, calendar (13.3) — including that a write is reflected within the TTL even if eviction is somehow missed.
- [ ] p95 targets met for reports and check-in/out under representative load (14.3).
- [ ] Large exports stream rather than buffer in memory (5.3).

**Observability**
- [ ] Structured JSON logs with correlation id + actor id flowing in staging, at the correct level, with no PII (14.1).
- [ ] Sentry, Actuator metrics and alerts (job failure, email-failure rate, login-failure spikes, error rate) live and tested by deliberately triggering each one (14.2).

**Security** (full list in Section 15 — restated here as a launch gate, not repeated in full)
- [ ] MFA enforced according to each organization's MFA policy (a required user gets no session without it; an optional user is never blocked); step-up auth verified for every destructive action (8.3).
- [ ] Rate limiting active on login, reset, approvals, check-in/out, exports, agent endpoints (15).
- [ ] Dependency/SAST/secret scans clean; SonarQube for IDE findings triaged, not just silenced (21, 16.2).
- [ ] SPF/DKIM/DMARC verified for the sending domain (9.2, R3).

**Resilience**
- [ ] Restore drill completed successfully against a real backup (14.4).
- [ ] Scheduled-job catch-up verified after simulated downtime (2, 14.3).
- [ ] Break-glass Super Admin recovery runbook tested at least once (3.3).

---


### 22.1 v9 launch gates — organization bootstrap, isolation and UI
- [ ] Founder can register an organization, verify email, complete setup (including choosing the organization's MFA policy, with optional founder MFA enrollment) and reach the real Super Admin dashboard.
- [ ] Registration transaction cannot leave an orphan organization or orphan founder.
- [ ] Employee invitation → private password → welcome → Employee dashboard works end-to-end.
- [ ] Direct Admin invitation → private password → MFA enrollment when the organization's policy requires it → Admin dashboard works end-to-end.
- [ ] Existing Employee promotion to Admin is step-up protected; MFA is enforced at the promoted Admin's next sign-in when the organization's policy requires it.
- [ ] Deactivated Employee/Admin cannot log in, refresh, use an existing session, receive tenant SSE, or use paired device authorization.
- [ ] Historical attendance/leave/audit rows remain valid after deactivation.
- [ ] Automated cross-tenant test suite proves Organization A cannot read/write/search/export/subscribe to Organization B.
- [ ] Login/reset/resend endpoints do not reveal whether an organization or account exists.
- [ ] No tenant-visible API exposes organization count/list/search.
- [ ] Redis/cache/SSE/job/export/email tests prove tenant scoping.
- [ ] Landing/Login/Register/Dashboard production pages pass approved visual-regression baselines at desktop/tablet/mobile sizes.
- [ ] Production UI uses Next.js/TypeScript/Tailwind/TanStack Query and approved motion stack; prototype HTML/inline JS is not shipped as the app architecture.
- [ ] `prefers-reduced-motion`, keyboard navigation, focus states, contrast and responsive states pass accessibility QA.

*End of v9. Frozen scope from the recorded B0 checkpoint through R4 unless revised here first, via a documented branch and PR.*

