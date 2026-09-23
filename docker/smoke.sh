#!/usr/bin/env bash
# Smoke verification of the backend development stack (docker-compose.yml; CLAUDE.md "B0-7 decisions").
#
#   bash docker/smoke.sh                 # from the repository root, or anywhere
#   SMOKE_KEEP=1 bash docker/smoke.sh    # leave the stack running afterwards, for debugging
#
# It builds the image, starts the whole stack from EMPTY volumes with throwaway random secrets, and checks what a
# deployment has to get right: the build is non-root and minimal, the stack becomes healthy, the migrations ran as the
# owner role while the application runs as the least-privileged runtime role (which cannot change the audit log),
# every container is hardened and limited, only loopback ports are published, an existing volume restarts cleanly,
# and shutdown is graceful within the stop grace period. CI runs it as the "compose-smoke" job.
#
# Hermetic: its own compose project (default peoplehub-smoke), its own volumes and host ports, and its own generated
# environment file. It never touches a developer's running dev stack or `.env`. Requires: docker (with compose), bash,
# curl, openssl. Docker must be running. Nothing is written into the repository.
set -Eeuo pipefail
# A command that aborts the script says so, with the line and the command (a bare "exit 2" is undiagnosable).
trap 'echo "smoke.sh: aborted at line $LINENO: $BASH_COMMAND" >&2' ERR

# Git Bash on Windows would otherwise rewrite arguments such as /mailpit into Windows paths.
export MSYS_NO_PATHCONV=1

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PROJECT="${SMOKE_PROJECT:-peoplehub-smoke}"
BACKEND_PORT="${SMOKE_BACKEND_PORT:-18080}"
MAILPIT_PORT="${SMOKE_MAILPIT_PORT:-18026}"

# The application's own margins (application.yml, CLAUDE.md B0-5 decisions): two shutdown phases plus the unwind period
# for an interrupted job. The extra margin keeps the grace period comfortably above the worst case.
UNWIND_SECONDS=5
GRACE_MARGIN_SECONDS=20

WORK="$(mktemp -d)"
ENV_FILE="$WORK/smoke.env"
CHECKS=0
FAILURES=0
STARTED=0

native_path() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

secret() { openssl rand -hex 16; }

# A throwaway ES256 signing key (b2-3), as one line of base64 (PKCS#8 DER): the application accepts PEM without its
# header and footer, and one line fits an env file.
jwt_signing_key() { openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 2>/dev/null | grep -v -- ----- | tr -d '\r\n'; }

cat >"$ENV_FILE" <<EOF
POSTGRES_BOOTSTRAP_PASSWORD=$(secret)
PEOPLEHUB_DB_OWNER_PASSWORD=$(secret)
PEOPLEHUB_DB_RUNTIME_PASSWORD=$(secret)
REDIS_PASSWORD=$(secret)
PEOPLEHUB_JWT_SIGNING_KEY=$(jwt_signing_key)
PEOPLEHUB_JWT_SIGNING_KEY_ID=smoke-key
PEOPLEHUB_BACKEND_PORT=$BACKEND_PORT
PEOPLEHUB_MAILPIT_UI_PORT=$MAILPIT_PORT
EOF

env_value() { grep -E "^$1=" "$ENV_FILE" | head -1 | cut -d= -f2-; }

# Compose defaults that docker/smoke.sh relies on; the compose file is the source of truth for them.
DB_NAME=peoplehub
OWNER_ROLE=peoplehub_owner
RUNTIME_ROLE=peoplehub_app
BOOTSTRAP_USER=pgbootstrap
RUNTIME_PASSWORD="$(env_value PEOPLEHUB_DB_RUNTIME_PASSWORD)"
OWNER_PASSWORD="$(env_value PEOPLEHUB_DB_OWNER_PASSWORD)"

compose() { docker compose -p "$PROJECT" --env-file "$(native_path "$ENV_FILE")" "$@"; }

cleanup() {
    local status=$?
    if [ "$status" -ne 0 ] || [ "$FAILURES" -ne 0 ]; then
        if [ "$STARTED" -eq 1 ]; then
            echo
            echo "---- last container logs (the run failed) ----"
            compose logs --no-color --tail 120 2>&1 | sed -E 's/(PASSWORD|password)=[^ ]+/\1=***/g' || true
        fi
    fi
    if [ "${SMOKE_KEEP:-0}" = "1" ]; then
        echo "SMOKE_KEEP=1: leaving project '$PROJECT' running (env file: $ENV_FILE)."
    else
        compose down -v --remove-orphans >/dev/null 2>&1 || true
        rm -rf "$WORK"
    fi
}
trap cleanup EXIT

pass() { CHECKS=$((CHECKS + 1)); echo "  ok    $1"; }
fail() { CHECKS=$((CHECKS + 1)); FAILURES=$((FAILURES + 1)); echo "  FAIL  $1"; [ -z "${2:-}" ] || echo "        $2"; }

# check "description" command args...   (runs the command; passes when it succeeds)
check() {
    local description="$1"
    shift
    if "$@" >/dev/null 2>&1; then pass "$description"; else fail "$description"; fi
}

# expect_contains "description" "text that must appear" "actual output"
# Matched by bash itself, not `printf | grep -q`: grep -q exits at the first match, which kills printf with SIGPIPE on
# large input (a whole startup log), and pipefail then reports the pipeline as failed although the text was found.
expect_contains() {
    if [[ "$3" == *"$2"* ]]; then pass "$1"; else fail "$1" "expected to find: $2"; fi
}

expect_equals() {
    if [ "$2" = "$3" ]; then pass "$1"; else fail "$1" "expected '$2', got '$3'"; fi
}

section() { echo; echo "== $1"; }

# -a: also finds a container that has been stopped (the shutdown check inspects it afterwards).
cid() { compose ps -aq "$1"; }

inspect() { docker inspect "$(cid "$1")" --format "$2"; }

# psql inside the db container, as the bootstrap superuser (local socket) ...
psql_admin() { compose exec -T db psql -U "$BOOTSTRAP_USER" -d "$DB_NAME" -Atc "$1"; }

# ... and over TCP as an application role (so the role's own privileges apply). Never fails the script: prints output.
psql_as() {
    compose exec -T -e PGPASSWORD="$2" db psql -h 127.0.0.1 -U "$1" -d "$DB_NAME" -Atc "$3" 2>&1 || true
}

# 30s -> 30, 2m -> 120, 45 -> 45
to_seconds() {
    case "$1" in
        *m) echo $((${1%m} * 60)) ;;
        *s) echo "${1%s}" ;;
        *) echo "$1" ;;
    esac
}

wait_ready() {
    local url="http://127.0.0.1:$BACKEND_PORT/actuator/health/readiness"
    for _ in $(seq 1 30); do
        if curl -sS "$url" 2>/dev/null | grep -q '"status":"UP"'; then return 0; fi
        sleep 2
    done
    return 1
}

echo "Smoke test of the backend development stack (project: $PROJECT, backend port: $BACKEND_PORT)"

# ---------------------------------------------------------------------------------------------------------------------
section "Static checks"
check "docker-compose.yml is valid" compose config -q
tests_pg="$(sed -n 's/.*POSTGRES_IMAGE = "\([^"]*\)".*/\1/p' src/test/java/com/peoplehub/support/TestcontainersConfiguration.java)"
tests_redis="$(sed -n 's/.*REDIS_IMAGE = "\([^"]*\)".*/\1/p' src/test/java/com/peoplehub/support/TestcontainersConfiguration.java)"
expect_contains "compose uses the same PostgreSQL image tag as the tests ($tests_pg)" "image: $tests_pg@sha256:" "$(grep -E '^ +image: postgres:' docker-compose.yml)"
expect_contains "compose uses the same Redis image tag as the tests ($tests_redis)" "image: $tests_redis@sha256:" "$(grep -E '^ +image: redis:' docker-compose.yml)"
: >"$WORK/empty.env"
missing="$(docker compose -p "$PROJECT-negative" --env-file "$(native_path "$WORK/empty.env")" config -q 2>&1 || true)"
expect_contains "a missing secret stops Compose before anything starts" "is missing a value" "$missing"
role_script=docker/postgres/init/01-roles.sh
if git ls-files --error-unmatch "$role_script" >/dev/null 2>&1; then
    mode="$(git ls-files -s "$role_script" | cut -d' ' -f1)"
    expect_equals "the role script is committed executable (mode 100755)" 100755 "$mode"
fi

# ---------------------------------------------------------------------------------------------------------------------
section "Build and start from empty volumes"
STARTED=1
compose up --build --wait --wait-timeout 300 >"$WORK/up.log" 2>&1 || {
    cat "$WORK/up.log"
    fail "the stack builds and every service becomes healthy"
    exit 1
}
pass "the stack builds and every service becomes healthy"
for service in db redis mailpit backend; do
    expect_equals "$service is healthy" healthy "$(inspect "$service" '{{.State.Health.Status}}')"
done

# ---------------------------------------------------------------------------------------------------------------------
section "Application"
check "readiness is UP (database and Redis reachable)" wait_ready
api_docs="$(curl -sS "http://127.0.0.1:$BACKEND_PORT/v3/api-docs" 2>&1 || true)"
expect_contains "the OpenAPI document is served" '"openapi"' "$api_docs"
# Deny by default (b2-3): an unauthenticated request to any non-public path, mapped or not, is a 401 problem.
response="$(curl -sS -i "http://127.0.0.1:$BACKEND_PORT/api/v1/smoke-does-not-exist" 2>&1 | tr -d '\r' || true)"
expect_contains "an unauthenticated request returns an RFC 9457 problem body" "Content-Type: application/problem+json" "$response"
expect_contains "the problem body carries a type URN" '"type":"urn:peoplehub:problem:unauthorized"' "$response"
expect_contains "the response carries a correlation id" "X-Correlation-Id:" "$response"
metrics="$(curl -s -w '%{http_code}' "http://127.0.0.1:$BACKEND_PORT/actuator/metrics" 2>&1 || true)"
expect_equals "Actuator exposes nothing but health without authentication (/actuator/metrics is 401)" 401 "${metrics: -3}"
me="$(curl -sS -i "http://127.0.0.1:$BACKEND_PORT/api/v1/me" 2>&1 | tr -d '\r' || true)"
expect_contains "GET /api/v1/me without a token is a 401" "HTTP/1.1 401" "$me"
expect_contains "the 401 is an RFC 9457 problem body" '"type":"urn:peoplehub:problem:unauthorized"' "$me"
bogus_key="smoke-not-a-signing-key-$(secret)"
bad_start="$(compose run --rm --no-deps -e PEOPLEHUB_JWT_SIGNING_KEY="$bogus_key" backend 2>&1)" && bad_status=0 || bad_status=$?
if [ "$bad_status" -ne 0 ]; then
    pass "the backend refuses to start with an invalid signing key (exit $bad_status)"
else
    fail "the backend refuses to start with an invalid signing key" "it exited 0"
fi
expect_contains "the startup failure points at the signing key loader" "JwtKeySet" "$bad_start"
if [[ "$bad_start" == *"$bogus_key"* ]]; then
    fail "the rejected signing key is not echoed in the logs"
else
    pass "the rejected signing key is not echoed in the logs"
fi
check "Mailpit is ready" compose exec -T mailpit /mailpit readyz
warn_count="$(compose logs --no-color backend 2>&1 | grep -cE '"level":"(WARN|ERROR)"' || true)"
expect_equals "the backend logged no warnings or errors while starting" 0 "$warn_count"
# Extracted with bash's own regex engine, not an external sed pipeline: a sed dialect/locale
# difference between a developer's machine and a CI runner is exactly the kind of thing that can make
# a regex silently stop matching on "another runner" while looking fine locally, and sed then leaves
# the line unchanged instead of failing loudly. [[ =~ ]] is bash itself, so the same code path runs
# identically everywhere this script runs.
timestamp_line="$(compose logs --no-color backend 2>&1 | grep -m1 '"@timestamp"' || true)"
first_stamp=""
if [[ "$timestamp_line" =~ \"@timestamp\":\"([^\"]*)\" ]]; then
    first_stamp="${BASH_REMATCH[1]}"
fi
# Regression guard: proves the extraction itself worked (a well-formed ISO-8601 instant), separately
# from the UTC/"Z" check below. Without this, a future extraction failure could leave first_stamp
# empty or malformed and "log timestamps are UTC" would just fail with an unhelpful "expected to
# find: Z" rather than pointing at the actual problem.
if [[ "$first_stamp" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2} ]]; then
    pass "the @timestamp field was found and looks like an ISO-8601 instant"
else
    fail "the @timestamp field was found and looks like an ISO-8601 instant" \
        "extracted '$first_stamp' from log line: $timestamp_line"
fi
expect_contains "log timestamps are UTC" "Z" "$first_stamp"

# ---------------------------------------------------------------------------------------------------------------------
section "Two database roles (CLAUDE.md, B0-6/3)"
migrators="$(psql_admin "select string_agg(distinct installed_by, ',') from flyway_schema_history")"
expect_equals "Flyway ran every migration as the owner role" "$OWNER_ROLE" "$migrators"
failed_migrations="$(psql_admin "select count(*) from flyway_schema_history where not success")"
expect_equals "no migration failed" 0 "$failed_migrations"
history_before="$(psql_admin "select count(*) from flyway_schema_history")"
owners="$(psql_admin "select string_agg(distinct tableowner, ',') from pg_tables where schemaname = 'public'")"
expect_equals "the owner role owns every table" "$OWNER_ROLE" "$owners"
db_owner="$(psql_admin "select pg_get_userbyid(datdba) from pg_database where datname = '$DB_NAME'")"
expect_equals "the owner role owns the database (so it needs no superuser)" "$OWNER_ROLE" "$db_owner"
superusers="$(psql_admin "select count(*) from pg_roles where rolsuper and rolname in ('$OWNER_ROLE','$RUNTIME_ROLE')")"
expect_equals "neither application role is a superuser" 0 "$superusers"
connected_as="$(psql_admin "select string_agg(distinct usename, ',') from pg_stat_activity where datname = '$DB_NAME' and usename in ('$OWNER_ROLE','$RUNTIME_ROLE')")"
expect_equals "the application connects as the runtime role only" "$RUNTIME_ROLE" "$connected_as"

expect_equals "the runtime role can read the audit log" 0 "$(psql_as "$RUNTIME_ROLE" "$RUNTIME_PASSWORD" "select count(*) from audit_log")"
for statement in "update audit_log set action = 'X'" "delete from audit_log" "truncate audit_log"; do
    expect_contains "runtime role, \"$statement\": denied" "permission denied for table audit_log" \
        "$(psql_as "$RUNTIME_ROLE" "$RUNTIME_PASSWORD" "$statement")"
done
for statement in "update audit_log set action = 'X'" "delete from audit_log" "truncate audit_log"; do
    expect_contains "owner role, \"$statement\": rejected by the trigger" "audit_log is append-only" \
        "$(psql_as "$OWNER_ROLE" "$OWNER_PASSWORD" "$statement")"
done

# ---------------------------------------------------------------------------------------------------------------------
section "Redis"
expect_contains "Redis refuses unauthenticated commands" "NOAUTH" "$(compose exec -T redis redis-cli ping 2>&1 || true)"
# Single quotes on purpose: $REDIS_PASSWORD must be expanded by the shell INSIDE the container, so the password never
# appears on the host's command line.
# shellcheck disable=SC2016
aof="$(compose exec -T redis sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning config get appendonly' 2>&1 | tr '\n' ' ' || true)"
expect_contains "AOF persistence is on" "yes" "$aof"

# ---------------------------------------------------------------------------------------------------------------------
section "Hardening, limits and published ports (all four services)"
for service in db redis mailpit backend; do
    expect_equals "$service: read-only root filesystem" true "$(inspect "$service" '{{.HostConfig.ReadonlyRootfs}}')"
    expect_equals "$service: all capabilities dropped" "[ALL]" "$(inspect "$service" '{{.HostConfig.CapDrop}}')"
    expect_contains "$service: no-new-privileges" "no-new-privileges" "$(inspect "$service" '{{.HostConfig.SecurityOpt}}')"
    user="$(inspect "$service" '{{.Config.User}}')"
    if [ -n "$user" ] && [ "${user%%:*}" != "0" ] && [ "${user%%:*}" != "root" ]; then
        pass "$service: runs as a non-root user ($user)"
    else
        fail "$service: runs as a non-root user" "Config.User is '$user'"
    fi
    memory="$(inspect "$service" '{{.HostConfig.Memory}}')"
    cpus="$(inspect "$service" '{{.HostConfig.NanoCpus}}')"
    if [ "$memory" -gt 0 ] && [ "$cpus" -gt 0 ]; then
        pass "$service: memory and CPU limits are set"
    else
        fail "$service: memory and CPU limits are set" "memory=$memory nanocpus=$cpus"
    fi
done
for service in db redis; do
    expect_equals "$service: no port is published to the host" "" "$(docker port "$(cid "$service")" 2>&1)"
done
for service in backend mailpit; do
    published="$(docker port "$(cid "$service")" 2>&1)"
    if [ -n "$published" ] && ! printf '%s' "$published" | grep -vqE '127\.0\.0\.1:'; then
        pass "$service: published on 127.0.0.1 only"
    else
        fail "$service: published on 127.0.0.1 only" "$published"
    fi
done
expect_equals "the backend container runs as uid 10001" 10001 "$(compose exec -T backend id -u)"

# ---------------------------------------------------------------------------------------------------------------------
section "Image"
image=peoplehub-backend:dev
expect_equals "the image user is numeric and unprivileged" "10001:10001" "$(docker image inspect "$image" --format '{{.Config.User}}')"
expect_contains "the image runs in UTC" "TZ=UTC" "$(docker image inspect "$image" --format '{{range .Config.Env}}{{println .}}{{end}}')"
expect_contains "the image has a health check" "actuator/health/liveness" "$(docker image inspect "$image" --format '{{.Config.Healthcheck.Test}}')"
tools="$(docker run --rm --entrypoint sh "$image" -c 'for b in javac mvn git; do command -v $b; done; true' 2>&1 || true)"
expect_equals "the runtime image has no compiler or build tools" "" "$tools"
app_files="$(docker run --rm --entrypoint sh "$image" -c 'ls -A /app' 2>&1 || true)"
expect_equals "the image contains only the application jar in /app (no .env, no sources)" "app.jar" "$app_files"

# ---------------------------------------------------------------------------------------------------------------------
section "Shutdown grace (CLAUDE.md, B0-5 decisions)"
grace="$(inspect backend '{{.Config.StopTimeout}}')"
phase="$(inspect backend '{{range .Config.Env}}{{println .}}{{end}}' | sed -n 's/^SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE=//p')"
phase_seconds="$(to_seconds "${phase:-30s}")"
required=$((2 * phase_seconds + UNWIND_SECONDS + GRACE_MARGIN_SECONDS))
if [ "$grace" -ge 90 ] && [ "$grace" -ge "$required" ]; then
    pass "stop grace period ${grace}s is at least 90s and at least 2 x ${phase_seconds}s + ${UNWIND_SECONDS}s + ${GRACE_MARGIN_SECONDS}s (${required}s)"
else
    fail "stop grace period is at least 90s and at least ${required}s" "it is ${grace}s"
fi

# ---------------------------------------------------------------------------------------------------------------------
section "Restart on existing volumes (the role script must not run again)"
compose down >/dev/null 2>&1
compose up -d --wait --wait-timeout 300 >"$WORK/up2.log" 2>&1 || { cat "$WORK/up2.log"; fail "the stack restarts on its existing volumes"; exit 1; }
pass "the stack restarts on its existing volumes"
check "readiness is UP again" wait_ready
expect_equals "no migration ran again" "$history_before" "$(psql_admin "select count(*) from flyway_schema_history")"

# ---------------------------------------------------------------------------------------------------------------------
section "Graceful shutdown"
started_at=$(date +%s)
compose stop backend >/dev/null 2>&1
elapsed=$(($(date +%s) - started_at))
exit_code="$(inspect backend '{{.State.ExitCode}}')"
shutdown_log="$(compose logs --no-color backend 2>&1)"
if [ "$elapsed" -lt "$grace" ]; then pass "the backend stopped in ${elapsed}s (grace ${grace}s)"; else fail "the backend stopped within the grace period" "${elapsed}s"; fi
if [ "$exit_code" = "0" ] || [ "$exit_code" = "143" ]; then
    pass "the backend exited on SIGTERM (exit code $exit_code; 143 is normal for a JVM)"
else
    fail "the backend exited on SIGTERM" "exit code $exit_code"
fi
expect_contains "Spring's graceful shutdown ran" "Graceful shutdown complete" "$shutdown_log"
expect_equals "it was not killed for lack of memory" false "$(inspect backend '{{.State.OOMKilled}}')"

# ---------------------------------------------------------------------------------------------------------------------
echo
echo "$CHECKS checks, $FAILURES failed"
if [ "$FAILURES" -ne 0 ]; then
    exit 1
fi
