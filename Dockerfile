# syntax=docker/dockerfile:1
#
# PeopleHub backend image (Spec 16.4, D16; CLAUDE.md, B0-7 decisions).
#
# Multi-stage: a JDK stage compiles, a JRE-only stage runs. The runtime image is non-root, uses UTC, has an Actuator
# health check, and never contains the tests, the build tools or any environment file (see .dockerignore).
#
# Base images: Temurin 21 on Ubuntu noble (glibc). Chosen over Alpine for reliability, not size: later phases add
# libraries that ship glibc-linked native code (for example password hashing) and Apache POI needs fonts. Pinned by
# digest so a build is reproducible; there is no automatic updater yet, so bump the digests deliberately (README,
# "Docker") and rebuild.

# ---- build stage ----------------------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-noble@sha256:4d271cd5e0624598cf563342f47281b09cb364bc13acbbd7251f49f83470018d AS build

WORKDIR /workspace

# Dependency layer first: only a change to the wrapper or the pom invalidates it.
COPY .mvn .mvn
COPY mvnw pom.xml ./

# Tests are not run here (CI runs them) and are not in the build context, so a test edit never invalidates the layer.
COPY src/main src/main
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -Dmaven.test.skip=true package

# ---- runtime stage --------------------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-noble@sha256:7739f0ffce786528961eea6bf46d9610ee968ac6127c9b2e93494757bdecce9f

# The organization's time logic is explicit (Clock, org timezone) and the database is UTC; this only fixes the zone the
# JVM stamps log lines with, so they are UTC wherever the container runs (closes the B0-4 "log timestamp zone" item).
ENV TZ=UTC

# A numeric, unprivileged user (Kubernetes' runAsNonRoot needs a numeric id). No home directory, no login shell.
RUN groupadd --gid 10001 peoplehub \
 && useradd --uid 10001 --gid 10001 --system --no-create-home --shell /usr/sbin/nologin peoplehub

WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/target/peoplehub-backend-*.jar /app/app.jar

USER 10001:10001
EXPOSE 8080

# Liveness only (B0-4 D1): it must not depend on PostgreSQL or Redis, so an outage of either makes the instance not
# ready without getting the container restarted. Readiness (/actuator/health/readiness) also checks both.
# start-period covers Flyway and startup.
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=5 \
    CMD curl -fsS -o /dev/null http://127.0.0.1:8080/actuator/health/liveness

# Exec form: the JVM is PID 1 and receives SIGTERM directly, so Spring's graceful shutdown runs (it exits 143, which is
# normal for a JVM stopped by SIGTERM). The heap follows the container's memory limit.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
