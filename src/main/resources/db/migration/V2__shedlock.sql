-- V2: lock table for ShedLock (Spec 2, 14.3). A scheduled job runs on one instance at a time by taking a row here.
--
-- EXCEPTION to "timestamptz everywhere" (CLAUDE.md 9): the two time columns (`lock_until`, `locked_at`) are
-- plain `timestamp`, holding UTC wall clock time. This is ShedLock's documented PostgreSQL schema and it is
-- deliberate. With `usingDbTime()` ShedLock writes
-- `timezone('utc', CURRENT_TIMESTAMP)`, which is a timestamp WITHOUT time zone. Stored in a timestamptz column, Postgres
-- re-reads it in each connection's session time zone (which the JDBC driver takes from the JVM), so two instances in
-- different zones disagree about when a lock expires: measured against real PostgreSQL, a live lock was stolen (double
-- execution) or stuck for hours after a crash in 5 of 6 zone pairs. Plain `timestamp` behaved correctly in all 6.
-- ShedLockTimeZoneTest keeps this honest. Nothing outside ShedLock reads or writes this table.
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    CONSTRAINT pk_shedlock PRIMARY KEY (name)
);

COMMENT ON TABLE shedlock IS 'ShedLock job locks. UTC wall-clock timestamps on purpose (see V2 header); not a business table.';
