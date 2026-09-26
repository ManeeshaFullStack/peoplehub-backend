-- V19: organization MFA policy, per-employee MFA state and three new refresh-token revoke reasons (b2-7, B2-7/1,
-- B2-7/3, B2-7/5, B2-7/6, B2-7/12, B2-7/18, B2-7/27; Spec D6, 8.3, 11). Depends on organization (V8), employee (V9,
-- V16) and refresh_token (V11, V14, V16, V18).
--
-- Decisions: CLAUDE.md "B2-7 decisions" and "MFA policy decisions".
--  * organization.mfa_policy: one of the five policy values, DISABLED by default (MFA/3), so every existing and every
--    new organization starts with MFA off. Stored on organization, not in the generic org-settings store (b3-6).
--  * employee.mfa_required: the Super Admin's selection for REQUIRED_FOR_SELECTED_USERS. It may be set under any policy
--    (preparation) but is enforced only while the policy is REQUIRED_FOR_SELECTED_USERS.
--  * employee.mfa_enrolled_at: when enrollment was confirmed. ck_employee_mfa_state_consistent keeps it in step with
--    V9's mfa_enabled and mfa_totp_secret: an enabled account always has a secret and an enrollment time; a disabled
--    one has no enrollment time, and may hold a pending (not yet confirmed) secret.
--  * employee.mfa_totp_last_step: the last accepted TOTP time step, so one code cannot be used twice (B2-7/5).
--  * employee.mfa_reminder_dismissed_at: the server-side reminder dismissal (B2-7/27).
--  * refresh_token.revoke_reason gains MFA_REQUIRED (a policy change or selection newly requires MFA of someone not
--    enrolled), MFA_RESET (an authorized reset of another person's MFA) and ROLE_CHANGED (promotion). V18's CHECK is
--    replaced, not edited. None of the three is a reuse reason: presenting such a token is simply refused.
--
-- PRECONDITION: identical guard to V3-V18 -- the runtime role must already exist.
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
-- Organization MFA policy.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE organization
    ADD COLUMN mfa_policy VARCHAR(32) NOT NULL DEFAULT 'DISABLED';

ALTER TABLE organization
    ADD CONSTRAINT ck_organization_mfa_policy
        CHECK (mfa_policy IN ('DISABLED', 'OPTIONAL', 'REQUIRED_FOR_ADMINS', 'REQUIRED_FOR_SELECTED_USERS',
                              'REQUIRED_FOR_ALL'));

COMMENT ON COLUMN organization.mfa_policy IS 'b2-7 (V19): DISABLED (default; MFA not offered), OPTIONAL (never blocks login; voluntary), REQUIRED_FOR_ADMINS, REQUIRED_FOR_SELECTED_USERS, REQUIRED_FOR_ALL. Set by a Super Admin (Spec 8.3).';

-- ---------------------------------------------------------------------------------------------
-- Per-employee MFA state.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE employee
    ADD COLUMN mfa_required               BOOLEAN     NOT NULL DEFAULT false,
    ADD COLUMN mfa_enrolled_at            TIMESTAMPTZ,
    ADD COLUMN mfa_totp_last_step         BIGINT,
    ADD COLUMN mfa_reminder_dismissed_at  TIMESTAMPTZ;

ALTER TABLE employee
    ADD CONSTRAINT ck_employee_mfa_state_consistent
        CHECK ((mfa_enabled AND mfa_totp_secret IS NOT NULL AND mfa_enrolled_at IS NOT NULL)
            OR (NOT mfa_enabled AND mfa_enrolled_at IS NULL)),
    ADD CONSTRAINT ck_employee_mfa_totp_last_step_not_negative
        CHECK (mfa_totp_last_step IS NULL OR mfa_totp_last_step >= 0);

COMMENT ON COLUMN employee.mfa_required IS 'b2-7 (V19): selected by a Super Admin. Settable under any policy; enforced only while organization.mfa_policy is REQUIRED_FOR_SELECTED_USERS.';
COMMENT ON COLUMN employee.mfa_enrolled_at IS 'b2-7 (V19): when MFA enrollment was confirmed; set exactly when mfa_enabled is true. Application clock.';
COMMENT ON COLUMN employee.mfa_totp_last_step IS 'b2-7 (V19): the last accepted TOTP time step (RFC 6238), so a code cannot be replayed.';
COMMENT ON COLUMN employee.mfa_reminder_dismissed_at IS 'b2-7 (V19): when the person last dismissed the MFA reminder. Application clock.';

-- ---------------------------------------------------------------------------------------------
-- Revoke reasons: V18's list plus MFA_REQUIRED, MFA_RESET and ROLE_CHANGED.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE refresh_token
    DROP CONSTRAINT ck_refresh_token_revoke_reason,
    ADD CONSTRAINT ck_refresh_token_revoke_reason
        CHECK (revoke_reason IN ('ROTATED', 'LOGOUT', 'REUSE_DETECTED', 'PASSWORD_RESET', 'PASSWORD_CHANGED',
                                 'SESSION_REVOKED', 'DEACTIVATED', 'MFA_REQUIRED', 'MFA_RESET', 'ROLE_CHANGED'));

COMMENT ON COLUMN refresh_token.revoke_reason IS 'ROTATED (replaced by a refresh), LOGOUT, REUSE_DETECTED (the whole family was revoked), PASSWORD_RESET (every session ended by a reset), PASSWORD_CHANGED (other sessions ended by a change), SESSION_REVOKED (ended by the employee from their sessions list), DEACTIVATED (every session ended by deactivation), MFA_REQUIRED (MFA newly required of someone not enrolled), MFA_RESET (the person''s MFA was reset), ROLE_CHANGED (promotion).';

-- ---------------------------------------------------------------------------------------------
-- Runtime role: may update the policy and the new MFA columns, and, new in b2-7, employee.role (promotion). Still
-- never employee_code, email or email_normalized. No new refresh_token grant: V14 already allows revoke_reason.
-- ---------------------------------------------------------------------------------------------
DO $mig$
DECLARE
    runtime_role text := '${runtime_role}';
BEGIN
    EXECUTE format('GRANT UPDATE (mfa_policy) ON organization TO %I', runtime_role);
    EXECUTE format(
        'GRANT UPDATE (role, mfa_required, mfa_enrolled_at, mfa_totp_last_step, mfa_reminder_dismissed_at)'
        ' ON employee TO %I',
        runtime_role);
END
$mig$;
