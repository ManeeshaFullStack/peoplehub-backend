-- V20: mfa_recovery_code tenant binding and invalidation (b2-7, B2-7/8, B2-7/12; Spec 8.3, 12.1). Depends on
-- employee (V9, V14) and mfa_recovery_code (V11).
--
-- Decisions: CLAUDE.md "B2-7 decisions" and the approved B2-7 implementation plan.
--  * organization_id: backfilled from the owning employee, then NOT NULL. Nothing wrote mfa_recovery_code before
--    b2-7, so the backfill normally touches no row; it is there so the migration is correct on any database (the V14
--    pattern).
--  * A composite foreign key (organization_id, employee_id) -> employee (organization_id, id), so a recovery code can
--    never belong to an employee of another organization. V11's plain fk_mfa_recovery_code_employee stays
--    (forward-only; redundant, not wrong).
--  * invalidated_at: regenerating codes or an MFA reset makes the old codes unusable by setting this, never by
--    deleting them (the runtime role has no DELETE). A code is either used or invalidated, never both.
--
-- PRECONDITION: identical guard to V3-V19 -- the runtime role must already exist.
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
ALTER TABLE mfa_recovery_code
    ADD COLUMN organization_id UUID;

UPDATE mfa_recovery_code c
   SET organization_id = e.organization_id
  FROM employee e
 WHERE e.id = c.employee_id;

ALTER TABLE mfa_recovery_code
    ALTER COLUMN organization_id SET NOT NULL;

ALTER TABLE mfa_recovery_code
    ADD CONSTRAINT fk_mfa_recovery_code_employee_in_organization
        FOREIGN KEY (organization_id, employee_id) REFERENCES employee (organization_id, id);

-- ---------------------------------------------------------------------------------------------
-- Invalidation.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE mfa_recovery_code
    ADD COLUMN invalidated_at TIMESTAMPTZ;

ALTER TABLE mfa_recovery_code
    ADD CONSTRAINT ck_mfa_recovery_code_not_used_and_invalidated
        CHECK (used_at IS NULL OR invalidated_at IS NULL);

COMMENT ON COLUMN mfa_recovery_code.organization_id IS 'b2-7 (V20): the owning employee''s organization. The composite FK fk_mfa_recovery_code_employee_in_organization makes a cross-tenant code impossible.';
COMMENT ON COLUMN mfa_recovery_code.invalidated_at IS 'b2-7 (V20): when regeneration or an MFA reset made this code unusable. Set once; codes are never deleted.';

-- ---------------------------------------------------------------------------------------------
-- Runtime role: INSERT gains organization_id; UPDATE gains invalidated_at next to V11's used_at. Still no DELETE or
-- TRUNCATE, and never code_hash, employee_id or organization_id.
-- ---------------------------------------------------------------------------------------------
DO $mig$
DECLARE
    runtime_role text := '${runtime_role}';
BEGIN
    EXECUTE format('GRANT INSERT (organization_id) ON mfa_recovery_code TO %I', runtime_role);
    EXECUTE format('GRANT UPDATE (invalidated_at) ON mfa_recovery_code TO %I', runtime_role);
END
$mig$;
