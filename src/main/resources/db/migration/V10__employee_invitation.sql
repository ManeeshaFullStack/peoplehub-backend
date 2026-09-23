-- V10: employee_invitation (b2-1, Spec 2.1.5, 3.3, 12.1). Depends on organization (V8) and
-- employee (V9, for the inviter).
--
-- Decisions: CLAUDE.md B2 planning ("B2-1 Implementation Plan" record, corrected). Enforces one
-- ACTIVE invitation per (organization, email) at the database level via a partial unique index
-- (WHERE consumed_at IS NULL AND revoked_at IS NULL), the same idiom Spec 4.2 already uses for
-- attendance_session's "one open session per employee" rule -- a second invite to the same address
-- can only be created once the first is consumed or revoked.
--
-- PRECONDITION: identical guard to V3-V9 -- the runtime role must already exist.
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

-- Real FKs to organization and employee from the start, same reasoning as V9 (this is itself part
-- of B2, not a table built before the organization/employee tables existed).
CREATE TABLE employee_invitation (
    id                    UUID         NOT NULL DEFAULT gen_random_uuid(),
    organization_id       UUID         NOT NULL,
    email_normalized      VARCHAR(254) NOT NULL,
    intended_role         VARCHAR(16)  NOT NULL,
    token_hash            VARCHAR(255) NOT NULL,
    inviter_employee_id   UUID         NOT NULL,
    expires_at            TIMESTAMPTZ  NOT NULL,
    consumed_at           TIMESTAMPTZ,
    revoked_at            TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_employee_invitation PRIMARY KEY (id),
    CONSTRAINT fk_employee_invitation_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_employee_invitation_inviter
        FOREIGN KEY (inviter_employee_id) REFERENCES employee (id),
    CONSTRAINT ck_employee_invitation_email_normalized_not_blank
        CHECK (char_length(email_normalized) > 0),
    CONSTRAINT ck_employee_invitation_email_normalized_is_normalized
        CHECK (email_normalized = lower(btrim(email_normalized))),
    -- Never SUPER_ADMIN: only the founder becomes one, through registration (D23), not invitation.
    CONSTRAINT ck_employee_invitation_intended_role
        CHECK (intended_role IN ('ADMIN', 'EMPLOYEE')),
    CONSTRAINT ck_employee_invitation_token_hash_not_blank
        CHECK (char_length(token_hash) > 0)
);

-- One active (not yet consumed or revoked) invitation per organization+email. A resent or replaced
-- invitation revokes the old row first, then a new one can be created.
CREATE UNIQUE INDEX uq_employee_invitation_active_per_org_email
    ON employee_invitation (organization_id, email_normalized)
    WHERE consumed_at IS NULL AND revoked_at IS NULL;

-- Supports the activation lookup by token.
CREATE INDEX idx_employee_invitation_token_hash
    ON employee_invitation (token_hash);

COMMENT ON TABLE employee_invitation IS 'Single-use, expiring invitations (Spec 2.1.5, 3.3). Fixes organization + intended role; the invitee cannot alter either.';
COMMENT ON COLUMN employee_invitation.token_hash IS 'Hashed at rest, same discipline as refresh_token.token_hash and mfa_recovery_code.code_hash. The raw token is never stored.';
COMMENT ON COLUMN employee_invitation.intended_role IS 'ADMIN or EMPLOYEE only -- never SUPER_ADMIN (D23: only the founder becomes one, through registration).';
COMMENT ON COLUMN employee_invitation.created_at IS 'Database time (now()); the application never supplies it.';

-- Runtime role: SELECT, INSERT on the writer-supplied columns (not id/created_at, database-
-- generated), UPDATE on consumed_at and revoked_at only (two distinct lifecycle events, granted
-- together since both are simple one-time timestamp writes with no other column involved).
REVOKE ALL ON employee_invitation FROM PUBLIC;

DO $mig$
DECLARE
    runtime_role text := '${runtime_role}';
BEGIN
    EXECUTE format('GRANT SELECT ON employee_invitation TO %I', runtime_role);
    EXECUTE format(
        'GRANT INSERT (organization_id, email_normalized, intended_role, token_hash,'
        ' inviter_employee_id, expires_at) ON employee_invitation TO %I',
        runtime_role);
    EXECUTE format(
        'GRANT UPDATE (consumed_at, revoked_at) ON employee_invitation TO %I',
        runtime_role);
END
$mig$;
