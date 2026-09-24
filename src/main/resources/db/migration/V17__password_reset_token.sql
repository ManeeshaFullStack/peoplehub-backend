-- V17: password_reset_token (b2-5, B2-5/P6, P7, P8, P11; Spec 8.2, 12.1). Depends on organization (V8) and
-- employee's UNIQUE (organization_id, id) (V14).
--
-- Decisions: CLAUDE.md "B2-5 decisions".
--  * token_hash only, never the raw token: the same discipline as organization_verification_token, employee_invitation
--    and refresh_token. UNIQUE, since a reset is looked up by its token's hash.
--  * Single use (consumed_at) and short-lived (expires_at, 30 minutes by default, set by the application's clock).
--    Requesting a new reset invalidates the older unused ones (invalidated_at). A token is never both consumed and
--    invalidated.
--  * Tenant-bound by a composite foreign key (organization_id, employee_id) -> employee (organization_id, id), so a
--    reset can never name an employee of another organization (Spec 12.1; the V14/V15 pattern).
--  * (employee_id, created_at) is indexed for the forgot-password throttle (B2-5/P7: at most one email per account per
--    5 minutes and 5 per day), which is derived from these rows.
--
-- PRECONDITION: identical guard to V3-V16 -- the runtime role must already exist.
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

CREATE TABLE password_reset_token (
    id               UUID         NOT NULL DEFAULT gen_random_uuid(),
    organization_id  UUID         NOT NULL,
    employee_id      UUID         NOT NULL,
    token_hash       VARCHAR(255) NOT NULL,
    expires_at       TIMESTAMPTZ  NOT NULL,
    consumed_at      TIMESTAMPTZ,
    invalidated_at   TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_password_reset_token PRIMARY KEY (id),
    CONSTRAINT fk_password_reset_token_employee_in_organization
        FOREIGN KEY (organization_id, employee_id) REFERENCES employee (organization_id, id),
    CONSTRAINT uq_password_reset_token_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_password_reset_token_token_hash_not_blank
        CHECK (char_length(token_hash) > 0),
    CONSTRAINT ck_password_reset_token_not_consumed_and_invalidated
        CHECK (consumed_at IS NULL OR invalidated_at IS NULL)
);

CREATE INDEX idx_password_reset_token_employee_created
    ON password_reset_token (employee_id, created_at);

COMMENT ON TABLE password_reset_token IS 'Single-use, short-lived password reset tokens (b2-5, Spec 8.2), hashed at rest. Tenant-bound by a composite FK to employee.';
COMMENT ON COLUMN password_reset_token.token_hash IS 'Hashed at rest; the raw token exists only in the reset email.';
COMMENT ON COLUMN password_reset_token.consumed_at IS 'When the reset was completed. Set once.';
COMMENT ON COLUMN password_reset_token.invalidated_at IS 'When a newer reset request made this one unusable. Set once.';
COMMENT ON COLUMN password_reset_token.created_at IS 'Database time (now()); the application never supplies it. Also the basis of the forgot-password throttle.';

REVOKE ALL ON password_reset_token FROM PUBLIC;

-- Runtime role: SELECT; INSERT on the writer-supplied columns (not id/created_at, database-generated; not
-- consumed_at/invalidated_at, a new token is never used); UPDATE on consumed_at and invalidated_at only. No DELETE or
-- TRUNCATE.
DO $mig$
DECLARE
    runtime_role text := '${runtime_role}';
BEGIN
    EXECUTE format('GRANT SELECT ON password_reset_token TO %I', runtime_role);
    EXECUTE format(
        'GRANT INSERT (organization_id, employee_id, token_hash, expires_at)'
        ' ON password_reset_token TO %I',
        runtime_role);
    EXECUTE format(
        'GRANT UPDATE (consumed_at, invalidated_at) ON password_reset_token TO %I',
        runtime_role);
END
$mig$;
