-- V16: per-account lockout state and two new refresh-token revoke reasons (b2-5, B2-5/P3, P4, P13; Spec 8.2).
-- Depends on employee (V9) and refresh_token (V11, V14).
--
-- Decisions: CLAUDE.md "B2-5 decisions".
--  * employee.failed_login_count / employee.locked_until hold the per-account lockout state (B2-5/P3). Kept in the
--    database, not Redis, so a Redis flush or outage cannot reset or lose it. failed_login_count counts consecutive
--    failures and is reset by a successful login or a password reset; locked_until is when the current lock ends (NULL
--    when not locked). The backoff itself (threshold, starting length, cap) is application configuration (B2-5/P4).
--    Per-IP lockout is not part of b2-5 (B2-5/P5): no column is added for it.
--  * refresh_token.revoke_reason gains PASSWORD_RESET (a completed reset ends every session) and PASSWORD_CHANGED (a
--    change ends the other sessions) (B2-5/P9, P13). V14's CHECK is replaced, not edited: dropped and re-added with the
--    longer list in this migration. Existing rows only ever hold the three V14 values, which stay allowed.
--
-- PRECONDITION: identical guard to V3-V14 -- the runtime role must already exist.
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
-- Per-account lockout state.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE employee
    ADD COLUMN failed_login_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN locked_until       TIMESTAMPTZ;

ALTER TABLE employee
    ADD CONSTRAINT ck_employee_failed_login_count_not_negative
        CHECK (failed_login_count >= 0);

COMMENT ON COLUMN employee.failed_login_count IS 'b2-5 (V16): consecutive failed sign-ins; reset by a successful login or a password reset.';
COMMENT ON COLUMN employee.locked_until IS 'b2-5 (V16): end of the current lock, or NULL when not locked. Application clock.';

-- ---------------------------------------------------------------------------------------------
-- Revoke reasons: V14's list plus PASSWORD_RESET and PASSWORD_CHANGED.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE refresh_token
    DROP CONSTRAINT ck_refresh_token_revoke_reason,
    ADD CONSTRAINT ck_refresh_token_revoke_reason
        CHECK (revoke_reason IN ('ROTATED', 'LOGOUT', 'REUSE_DETECTED', 'PASSWORD_RESET', 'PASSWORD_CHANGED'));

COMMENT ON COLUMN refresh_token.revoke_reason IS 'ROTATED (replaced by a refresh), LOGOUT, REUSE_DETECTED (the whole family was revoked), PASSWORD_RESET (every session ended by a reset), PASSWORD_CHANGED (other sessions ended by a change).';

-- ---------------------------------------------------------------------------------------------
-- Runtime role: may update only the two lockout columns (in addition to V9's grants). No new
-- refresh_token grant is needed: V14 already grants UPDATE on revoke_reason.
-- ---------------------------------------------------------------------------------------------
DO $mig$
DECLARE
    runtime_role text := '${runtime_role}';
BEGIN
    EXECUTE format(
        'GRANT UPDATE (failed_login_count, locked_until) ON employee TO %I', runtime_role);
END
$mig$;
