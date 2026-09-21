# peoplehub-backend

Spring Boot API for **PeopleHub**, a multi-role HR operations app (attendance, leave, approvals, org structure,
notifications, reports) for a single organization.

- **Source of truth:** [`PROJECT_MASTER_SPEC.md`](PROJECT_MASTER_SPEC.md) (v8). Scope changes update the spec first,
  through a branch and pull request.
- **Engineering rules:** [`CLAUDE.md`](CLAUDE.md) (workflow, architecture, security, testing, known spec issues).
- **Status:** Phase B0 (Foundation) in progress. Currently there is a runnable skeleton (database, Flyway and Redis
  wired up) with no business endpoints yet.

## Stack

Java 21, Spring Boot 4.1.1, Maven (wrapper), PostgreSQL 17, Flyway, Hibernate, Redis 7, Testcontainers. Later phases add
ShedLock, OpenAPI, logging and the rest as the spec's build order (Section 17) reaches them.

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
| Run against your own Postgres/Redis (needs the environment below) | `./mvnw spring-boot:run` |
| Run the packaged jar (needs the environment below) | `java -jar target/peoplehub-backend-0.1.0-SNAPSHOT.jar` |

### Configuration

The app reads its connections from environment variables, using Spring's standard names. See
[`.env.example`](.env.example) for the full list; keep real values in a git-ignored `.env`.

| Variable | Purpose |
|---|---|
| `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | PostgreSQL |
| `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_PORT`, `SPRING_DATA_REDIS_PASSWORD` | Redis |

Nothing has a default: a missing database setting stops startup immediately. `spring-boot:test-run` needs none of these.
Flyway applies the migrations in `src/main/resources/db/migration` on startup. Migrations are forward-only; never edit
one that has already been merged, add a new `V<n>__description.sql` instead.

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
.github/                                # CI workflow, Dependabot, pull request template
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

## Logging & observability

**Logs** are one JSON object per line (Spring Boot's structured logging, `logstash` format) carrying `correlationId`
(the same id as the `X-Correlation-Id` header and the error body) and `actorId` (`anonymous` until authentication
exists; later the employee id, or `SYSTEM`/a job name for scheduled jobs). Every API call writes one line:

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
**unconfirmed**) to finish. Keep it below the orchestrator's stop grace period.

**Sentry** is off unless `SENTRY_DSN` is set (also `SENTRY_ENVIRONMENT`, `SENTRY_RELEASE`). No tracing, no breadcrumbs.
Only `correlationId` and `actorId` tags, the exception type and frames and the log message template are sent.

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
