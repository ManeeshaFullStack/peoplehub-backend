-- V21: mfa_challenge (b2-7, B2-7/9, B2-7/10, B2-7/22; Spec 2.1.6, 8.3). Depends on employee (V9, V14).
--
-- Decisions: CLAUDE.md "B2-7 decisions" and the approved B2-7 implementation plan.
--  * A challenge is what a correct password step returns instead of a session when the person is enrolled
--    (CHALLENGE) or the organization's policy requires MFA of them and they are not enrolled (ENROLL). It is single
--    use, stored only as a hash (the SecureTokens discipline of every other token table), short-lived, and bound to
--    its organization and employee by a composite foreign key.
--  * device_label and ip record what the password step saw, for the session that is created and for audit only. A
--    different address at completion is never a reason to refuse it.
--  * failed_attempts counts wrong codes. The limit is the application's (B2-7/10), not repeated here.
--  * consumed_at (completed) and invalidated_at (too many wrong codes, an MFA reset, deactivation) are set once, never
--    both. Rows are never deleted (the runtime role has no DELETE); retention is B13's concern.
--
-- PRECONDITION: identical guard to V3-V20 -- the runtime role must already exist.
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

CREATE TABLE mfa_challenge (
    id               UUID         NOT NULL DEFAULT gen_random_uuid(),
    organization_id  UUID         NOT NULL,
    employee_id      UUID         NOT NULL,
    token_hash       VARCHAR(255) NOT NULL,
    purpose          VARCHAR(16)  NOT NULL,
    device_label     VARCHAR(255),
    ip               INET,
    expires_at       TIMESTAMPTZ  NOT NULL,
    failed_attempts  INTEGER      NOT NULL DEFAULT 0,
    consumed_at      TIMESTAMPTZ,
    invalidated_at   TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_mfa_challenge PRIMARY KEY (id),
    CONSTRAINT fk_mfa_challenge_employee_in_organization
        FOREIGN KEY (organization_id, employee_id) REFERENCES employee (organization_id, id),
    CONSTRAINT uq_mfa_challenge_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_mfa_challenge_token_hash_not_blank
        CHECK (char_length(token_hash) > 0),
    CONSTRAINT ck_mfa_challenge_purpose
        CHECK (purpose IN ('CHALLENGE', 'ENROLL')),
    CONSTRAINT ck_mfa_challenge_failed_attempts_not_negative
        CHECK (failed_attempts >= 0),
    CONSTRAINT ck_mfa_challenge_not_consumed_and_invalidated
        CHECK (consumed_at IS NULL OR invalidated_at IS NULL)
);

CREATE INDEX idx_mfa_challenge_employee_open
    ON mfa_challenge (organization_id, employee_id)
    WHERE consumed_at IS NULL AND invalidated_at IS NULL;

COMMENT ON TABLE mfa_challenge IS 'b2-7 (V21): the MFA step between a correct password and a session (Spec 8.3). Single use, hashed at rest, tenant-bound by a composite FK to employee. Never deleted.';
COMMENT ON COLUMN mfa_challenge.token_hash IS 'Hashed at rest; the raw challenge token exists only in the login response.';
COMMENT ON COLUMN mfa_challenge.purpose IS 'CHALLENGE (the person is enrolled: prove a TOTP or recovery code) or ENROLL (the policy requires MFA and the person is not enrolled).';
COMMENT ON COLUMN mfa_challenge.device_label IS 'The coarse device label of the password step (B2-6/3), copied to the session. Audit and session information only.';
COMMENT ON COLUMN mfa_challenge.ip IS 'The address of the password step. Audit only; a different address at completion is never rejected.';
COMMENT ON COLUMN mfa_challenge.expires_at IS 'Application clock.';
COMMENT ON COLUMN mfa_challenge.failed_attempts IS 'Wrong codes presented; the application invalidates the challenge at its limit.';
COMMENT ON COLUMN mfa_challenge.consumed_at IS 'When the challenge completed and the session was created. Set once.';
COMMENT ON COLUMN mfa_challenge.invalidated_at IS 'When the challenge was made unusable (too many wrong codes, an MFA reset, deactivation). Set once.';
COMMENT ON COLUMN mfa_challenge.created_at IS 'Database time (now()); the application never supplies it.';

REVOKE ALL ON mfa_challenge FROM PUBLIC;

-- Runtime role: SELECT; INSERT on the writer-supplied columns (not id/created_at, database-generated; not
-- failed_attempts/consumed_at/invalidated_at, a new challenge is unused); UPDATE on the three state columns only. No
-- DELETE or TRUNCATE.
DO $mig$
DECLARE
    runtime_role text := '${runtime_role}';
BEGIN
    EXECUTE format('GRANT SELECT ON mfa_challenge TO %I', runtime_role);
    EXECUTE format(
        'GRANT INSERT (organization_id, employee_id, token_hash, purpose, device_label, ip, expires_at)'
        ' ON mfa_challenge TO %I',
        runtime_role);
    EXECUTE format(
        'GRANT UPDATE (failed_attempts, consumed_at, invalidated_at) ON mfa_challenge TO %I',
        runtime_role);
END
$mig$;
