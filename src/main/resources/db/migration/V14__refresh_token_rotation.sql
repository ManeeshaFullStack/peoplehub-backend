-- V14: refresh_token rotation, revocation and tenant binding (b2-3, B2-3/5-B2-3/10; Spec 8.1, 12.1).
-- Depends on employee (V9) and refresh_token (V11).
--
-- Decisions: CLAUDE.md "B2-3 decisions" (implementation plan, V14). What this adds:
--  * refresh_token.organization_id: backfilled from the owning employee, then NOT NULL. Nothing
--    wrote refresh_token before b2-3, so the backfill normally touches no row; it is there so the
--    migration is correct on any database, not only an empty one.
--  * A composite foreign key (organization_id, employee_id) -> employee (organization_id, id), so a
--    refresh token can never name an employee of a different organization (Spec 12.1: tenant-safe
--    foreign keys). It needs UNIQUE (organization_id, id) on employee as its target; id alone is
--    already unique, so that constraint changes nothing about which employee rows are valid. V11's
--    plain fk_refresh_token_employee stays (forward-only; it is redundant, not wrong).
--  * revoked_at / revoke_reason / replaced_by_id: why and when a token stopped being usable, and
--    which token replaced it on rotation (reuse forensics, B2-3/7, B2-3/8). CHECKs keep them in
--    step with V11's revoked flag, so a row can never be revoked without a reason and a time, or
--    carry a reason while still usable. Later branches (sessions/deactivation, b2-6) extend the
--    reason list with a new migration.
--  * expires_at (sliding) can never pass absolute_expires_at (B2-3/5).
--
-- A pre-existing revoked row would have no reason or time and fail the new CHECK, stopping the
-- migration: correct, since nothing has legitimately written one (same reasoning as V12).
--
-- PRECONDITION: identical guard to V3-V13 -- the runtime role must already exist.
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
-- Tenant binding.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE employee
    ADD CONSTRAINT uq_employee_organization_id UNIQUE (organization_id, id);

ALTER TABLE refresh_token
    ADD COLUMN organization_id UUID;

UPDATE refresh_token rt
   SET organization_id = e.organization_id
  FROM employee e
 WHERE e.id = rt.employee_id;

ALTER TABLE refresh_token
    ALTER COLUMN organization_id SET NOT NULL;

ALTER TABLE refresh_token
    ADD CONSTRAINT fk_refresh_token_employee_in_organization
        FOREIGN KEY (organization_id, employee_id) REFERENCES employee (organization_id, id);

-- ---------------------------------------------------------------------------------------------
-- Rotation and revocation.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE refresh_token
    ADD COLUMN revoked_at      TIMESTAMPTZ,
    ADD COLUMN revoke_reason   VARCHAR(32),
    ADD COLUMN replaced_by_id  UUID;

ALTER TABLE refresh_token
    ADD CONSTRAINT fk_refresh_token_replaced_by
        FOREIGN KEY (replaced_by_id) REFERENCES refresh_token (id),
    ADD CONSTRAINT ck_refresh_token_revoke_reason
        CHECK (revoke_reason IN ('ROTATED', 'LOGOUT', 'REUSE_DETECTED')),
    ADD CONSTRAINT ck_refresh_token_revocation_consistent
        CHECK ((revoked AND revoked_at IS NOT NULL AND revoke_reason IS NOT NULL)
            OR (NOT revoked AND revoked_at IS NULL AND revoke_reason IS NULL)),
    ADD CONSTRAINT ck_refresh_token_replaced_only_when_rotated
        CHECK (replaced_by_id IS NULL OR revoke_reason = 'ROTATED'),
    ADD CONSTRAINT ck_refresh_token_expiry_within_absolute
        CHECK (expires_at <= absolute_expires_at);

COMMENT ON COLUMN refresh_token.organization_id IS 'The owning employee''s organization (b2-3). The composite FK fk_refresh_token_employee_in_organization makes a cross-tenant token impossible.';
COMMENT ON COLUMN refresh_token.revoked_at IS 'When the token stopped being usable; set exactly when revoked is true. Application clock.';
COMMENT ON COLUMN refresh_token.revoke_reason IS 'ROTATED (replaced by a refresh), LOGOUT, or REUSE_DETECTED (a revoked token was presented again, so the whole family was revoked).';
COMMENT ON COLUMN refresh_token.replaced_by_id IS 'The token that replaced this one on rotation; only set when revoke_reason is ROTATED.';

-- ---------------------------------------------------------------------------------------------
-- Runtime role privileges, column by column, no ALTER DEFAULT PRIVILEGES.
--  * INSERT gains organization_id (every new token is written with its tenant).
--  * UPDATE gains revoked_at, revoke_reason, replaced_by_id next to V11's revoked. Never
--    token_hash, family_id, the expiries, employee_id or organization_id: rotation inserts a new
--    row and revokes the old one, it never rewrites a token. Still no DELETE or TRUNCATE.
-- ---------------------------------------------------------------------------------------------
DO $mig$
DECLARE
    runtime_role text := '${runtime_role}';
BEGIN
    EXECUTE format('GRANT INSERT (organization_id) ON refresh_token TO %I', runtime_role);
    EXECUTE format(
        'GRANT UPDATE (revoked_at, revoke_reason, replaced_by_id) ON refresh_token TO %I',
        runtime_role);
END
$mig$;
