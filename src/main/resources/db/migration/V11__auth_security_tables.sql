-- V11: refresh_token, login_attempt, mfa_recovery_code (b2-1, Spec 8.1, 8.2, 8.3, 12).
-- Depends on employee (V9). Grouped together the same way V6 grouped notification/
-- notification_preference: three auth-support tables with no cross-dependencies on each other.
--
-- Decisions: CLAUDE.md B2 planning ("B2-1 Implementation Plan" record). login_attempt deliberately
-- carries NO organization_id/employee FK at all: a failed login for an unresolvable organization or
-- account has no real tenant to attach to, and B0-6/16's rule ("no tenant, no audit row -- do not
-- fabricate one") applies here just as directly as it did to audit_log. It belongs in this
-- dedicated security-telemetry table instead, made append-only by reusing audit_log's own trigger
-- function (one function, two tables) rather than duplicating it.
--
-- PRECONDITION: identical guard to V3-V10 -- the runtime role must already exist.
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

-- ---------------------------------------------------------------------------------------------
-- refresh_token: rotating refresh tokens with reuse-detection families (Spec 8.1).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE refresh_token (
    id                    UUID         NOT NULL DEFAULT gen_random_uuid(),
    employee_id           UUID         NOT NULL,
    token_hash            VARCHAR(255) NOT NULL,
    family_id             UUID         NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at            TIMESTAMPTZ  NOT NULL,
    absolute_expires_at   TIMESTAMPTZ  NOT NULL,
    revoked               BOOLEAN      NOT NULL DEFAULT false,
    device_label          VARCHAR(255),
    CONSTRAINT pk_refresh_token PRIMARY KEY (id),
    CONSTRAINT fk_refresh_token_employee
        FOREIGN KEY (employee_id) REFERENCES employee (id),
    CONSTRAINT uq_refresh_token_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_refresh_token_token_hash_not_blank
        CHECK (char_length(token_hash) > 0)
);

CREATE INDEX idx_refresh_token_family ON refresh_token (family_id);
CREATE INDEX idx_refresh_token_employee_revoked ON refresh_token (employee_id, revoked);

COMMENT ON TABLE refresh_token IS 'Rotating refresh tokens (Spec 8.1). A reused already-revoked token means theft: the whole family_id is revoked, not just the one row.';
COMMENT ON COLUMN refresh_token.token_hash IS 'Hashed at rest; the raw token value is never stored, same discipline as employee_invitation.token_hash.';
COMMENT ON COLUMN refresh_token.family_id IS 'Groups a rotation chain. Reuse of a revoked token in a family revokes every row sharing this id.';
COMMENT ON COLUMN refresh_token.created_at IS 'Database time (now()); the application never supplies it.';

REVOKE ALL ON refresh_token FROM PUBLIC;

-- ---------------------------------------------------------------------------------------------
-- login_attempt: security telemetry for lockout/backoff (Spec 8.2, 15). No FK to organization or
-- employee at all -- see the migration header comment.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE login_attempt (
    id                                 BIGINT       GENERATED ALWAYS AS IDENTITY,
    organization_login_key_attempted   VARCHAR(200) NOT NULL,
    email_attempted                    VARCHAR(254) NOT NULL,
    ip                                 INET,
    success                            BOOLEAN      NOT NULL,
    occurred_at                        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_login_attempt PRIMARY KEY (id),
    CONSTRAINT ck_login_attempt_org_login_key_not_blank
        CHECK (char_length(organization_login_key_attempted) > 0),
    CONSTRAINT ck_login_attempt_email_not_blank
        CHECK (char_length(email_attempted) > 0)
);

CREATE INDEX idx_login_attempt_org_key_occurred
    ON login_attempt (organization_login_key_attempted, occurred_at);
CREATE INDEX idx_login_attempt_ip_occurred
    ON login_attempt (ip, occurred_at);

COMMENT ON TABLE login_attempt IS 'Security telemetry for lockout/backoff (Spec 8.2, 15). No organization_id/employee_id FK: an attempt against an unresolvable organization or account has no real tenant to attach to (B0-6/16''s rule, reapplied). Append-only via the reused audit_log_reject_change() trigger function.';
COMMENT ON COLUMN login_attempt.organization_login_key_attempted IS 'What was typed, verbatim (not necessarily a real organization). Forensic data, not a lookup key into organization.';
COMMENT ON COLUMN login_attempt.occurred_at IS 'Database time (now()); the application never supplies it.';

CREATE TRIGGER trg_login_attempt_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON login_attempt
    FOR EACH STATEMENT
    EXECUTE FUNCTION audit_log_reject_change();

ALTER TABLE login_attempt ENABLE ALWAYS TRIGGER trg_login_attempt_append_only;

REVOKE ALL ON login_attempt FROM PUBLIC;

-- ---------------------------------------------------------------------------------------------
-- mfa_recovery_code: single-use TOTP recovery codes, hashed at rest (Spec 8.3).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE mfa_recovery_code (
    id           BIGINT       GENERATED ALWAYS AS IDENTITY,
    employee_id  UUID         NOT NULL,
    code_hash    VARCHAR(255) NOT NULL,
    used_at      TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_mfa_recovery_code PRIMARY KEY (id),
    CONSTRAINT fk_mfa_recovery_code_employee
        FOREIGN KEY (employee_id) REFERENCES employee (id),
    CONSTRAINT uq_mfa_recovery_code_code_hash UNIQUE (code_hash),
    CONSTRAINT ck_mfa_recovery_code_code_hash_not_blank
        CHECK (char_length(code_hash) > 0)
);

CREATE INDEX idx_mfa_recovery_code_employee_used ON mfa_recovery_code (employee_id, used_at);

COMMENT ON TABLE mfa_recovery_code IS 'Single-use TOTP recovery codes (Spec 8.3), hashed at rest -- same discipline as refresh_token.token_hash and employee_invitation.token_hash.';
COMMENT ON COLUMN mfa_recovery_code.used_at IS 'NULL means still valid. Set once, never cleared.';
COMMENT ON COLUMN mfa_recovery_code.created_at IS 'Database time (now()); the application never supplies it.';

REVOKE ALL ON mfa_recovery_code FROM PUBLIC;

-- ---------------------------------------------------------------------------------------------
-- Runtime role privileges, table by table, no ALTER DEFAULT PRIVILEGES.
-- ---------------------------------------------------------------------------------------------
DO $mig$
DECLARE
    runtime_role text := '${runtime_role}';
BEGIN
    -- refresh_token: SELECT, INSERT on the writer-supplied columns (not id/created_at), UPDATE on
    -- revoked only (rotation is insert-new + revoke-old, not mutating the token_hash in place).
    EXECUTE format('GRANT SELECT ON refresh_token TO %I', runtime_role);
    EXECUTE format(
        'GRANT INSERT (employee_id, token_hash, family_id, expires_at, absolute_expires_at,'
        ' device_label) ON refresh_token TO %I',
        runtime_role);
    EXECUTE format('GRANT UPDATE (revoked) ON refresh_token TO %I', runtime_role);

    -- login_attempt: SELECT (lockout-window queries), INSERT on every app-supplied column (not
    -- id/occurred_at). Never UPDATE/DELETE/TRUNCATE: the trigger enforces this for everyone, the
    -- same defence-in-depth pattern as audit_log (B0-6/2) -- privilege AND trigger, not either
    -- alone.
    EXECUTE format('GRANT SELECT ON login_attempt TO %I', runtime_role);
    EXECUTE format(
        'GRANT INSERT (organization_login_key_attempted, email_attempted, ip, success)'
        ' ON login_attempt TO %I',
        runtime_role);

    -- mfa_recovery_code: SELECT, INSERT (all ten codes written together at enrollment, same
    -- "identity and initial state written together" pattern as notification_preference, V6),
    -- UPDATE on used_at only.
    EXECUTE format('GRANT SELECT ON mfa_recovery_code TO %I', runtime_role);
    EXECUTE format(
        'GRANT INSERT (employee_id, code_hash) ON mfa_recovery_code TO %I', runtime_role);
    EXECUTE format('GRANT UPDATE (used_at) ON mfa_recovery_code TO %I', runtime_role);
END
$mig$;
