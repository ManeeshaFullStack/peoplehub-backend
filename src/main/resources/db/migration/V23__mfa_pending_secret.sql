-- V23: employee.mfa_totp_pending_secret for step-up re-enrollment (b2-7, B2-7/6, B2-7/14; Spec 8.3). Depends on
-- employee (V9, V19).
--
-- Decisions: CLAUDE.md "B2-7 decisions" (B2-7/6: enrolling again while enrolled needs step-up and replaces the secret
-- and every recovery code) and the owner's C5 decision (2026-09-26) on where the new secret waits until it is
-- confirmed.
--  * While a person is enrolled, mfa_totp_secret holds their active secret. A re-enrollment writes the new secret
--    here instead, so the active secret and the recovery codes keep working until the new authenticator is
--    confirmed; abandoning or failing a re-enrollment changes nothing. Confirming copies it into mfa_totp_secret and
--    clears it, in one transaction.
--  * Same format and binding as mfa_totp_secret: application-level AES-256-GCM ciphertext, keyId:base64, with the
--    organization and employee ids as associated data (B2-7/7). Never plaintext.
--  * ck_employee_mfa_pending_secret_enrolled: a pending secret only exists next to an active enrollment. A first
--    enrollment keeps using mfa_totp_secret with mfa_enabled false (C3), unchanged.
--
-- PRECONDITION: identical guard to V3-V22 -- the runtime role must already exist.
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

ALTER TABLE employee
    ADD COLUMN mfa_totp_pending_secret VARCHAR(512);

ALTER TABLE employee
    ADD CONSTRAINT ck_employee_mfa_pending_secret_enrolled
        CHECK (mfa_totp_pending_secret IS NULL OR mfa_enabled);

COMMENT ON COLUMN employee.mfa_totp_pending_secret IS 'b2-7 (V23): the new TOTP secret of a step-up re-enrollment, until it is confirmed. Application-level AES-256-GCM ciphertext bound to the account, like mfa_totp_secret; never plaintext. Only while mfa_enabled.';

-- ---------------------------------------------------------------------------------------------
-- Runtime role: may write the pending secret. Nothing else changes.
-- ---------------------------------------------------------------------------------------------
DO $mig$
DECLARE
    runtime_role text := '${runtime_role}';
BEGIN
    EXECUTE format('GRANT UPDATE (mfa_totp_pending_secret) ON employee TO %I', runtime_role);
END
$mig$;
