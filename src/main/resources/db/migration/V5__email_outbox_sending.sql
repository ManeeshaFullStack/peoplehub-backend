-- V5: email outbox sending -- partial index for the processor's scan, and the runtime role's
-- UPDATE grant on exactly the columns the processor writes (b1-2, Spec 9.2).
--
-- Decisions: CLAUDE.md, "B1-2 decisions" (see the b1-2 planning record). b1-1 (V4) deliberately left
-- these out ("indexes wait for the reader"; "UPDATE ... added by the b1-2 migration that actually
-- needs it"). b1-2 is that reader.
--
-- PRECONDITION: identical guard to V3/V4 -- the runtime role must already exist.
DO $mig$
DECLARE
    runtime_role text := '${runtime_role}';
BEGIN
    IF runtime_role !~ '^[a-z_][a-z0-9_]{0,62}$' THEN
        RAISE EXCEPTION 'Flyway placeholder runtime_role is not a valid role name';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = runtime_role) THEN
        RAISE EXCEPTION 'Runtime role "%" does not exist. Provision the database roles before migrating (README, Database roles).',
            runtime_role;
    END IF;
END
$mig$;

-- Partial index on exactly the rows the processor's due-row scan cares about (status IN
-- ('PENDING','RETRYING')); terminal rows (SENT/FAILED) are never scanned and so are never indexed.
-- Supports "SELECT id FROM email_outbox WHERE status IN ('PENDING','RETRYING') AND
-- (next_attempt_at IS NULL OR next_attempt_at <= ?) ORDER BY id LIMIT ?" (EmailOutboxProcessor).
CREATE INDEX idx_email_outbox_due ON email_outbox (next_attempt_at)
    WHERE status IN ('PENDING', 'RETRYING');

-- Runtime role: column-level UPDATE on exactly the six columns the processor writes.
--   * status, attempts, next_attempt_at, provider_message_id, error, last_attempt_at.
--   * NOT organization_id, recipient, type, payload, id or created_at: those stay immutable after
--     creation. No ALTER DEFAULT PRIVILEGES; this migration grants exactly what b1-2 needs, table by
--     table, same discipline as every migration before it.
DO $mig$
DECLARE
    runtime_role text := '${runtime_role}';
BEGIN
    EXECUTE format(
        'GRANT UPDATE (status, attempts, next_attempt_at, provider_message_id, error,'
        ' last_attempt_at) ON email_outbox TO %I', runtime_role);
END
$mig$;
