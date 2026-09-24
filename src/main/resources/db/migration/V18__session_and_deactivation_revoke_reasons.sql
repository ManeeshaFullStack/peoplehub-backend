-- V18: two new refresh-token revoke reasons for session management and deactivation (b2-6, B2-6/4, B2-6/5, B2-6/6,
-- B2-6/11; Spec 2.1.7, 8.2, D26). Depends on refresh_token (V11, V14, V16).
--
-- Decisions: CLAUDE.md "B2-6 decisions".
--  * refresh_token.revoke_reason gains SESSION_REVOKED (the employee ended one of their sessions, or every other one)
--    and DEACTIVATED (deactivation ends every session of the employee). V16's CHECK is replaced, not edited: dropped
--    and re-added with the longer list in this migration. Existing rows only ever hold the five V16 values, which stay
--    allowed.
--  * No new column and no new grant: a session is a refresh-token family (B2-6/1), and the runtime role can already
--    update revoked, revoked_at and revoke_reason (V11, V14).
--
-- PRECONDITION: identical guard to V3-V17 -- the runtime role must already exist.
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

ALTER TABLE refresh_token
    DROP CONSTRAINT ck_refresh_token_revoke_reason,
    ADD CONSTRAINT ck_refresh_token_revoke_reason
        CHECK (revoke_reason IN ('ROTATED', 'LOGOUT', 'REUSE_DETECTED', 'PASSWORD_RESET', 'PASSWORD_CHANGED',
                                 'SESSION_REVOKED', 'DEACTIVATED'));

COMMENT ON COLUMN refresh_token.revoke_reason IS 'ROTATED (replaced by a refresh), LOGOUT, REUSE_DETECTED (the whole family was revoked), PASSWORD_RESET (every session ended by a reset), PASSWORD_CHANGED (other sessions ended by a change), SESSION_REVOKED (ended by the employee from their sessions list), DEACTIVATED (every session ended by deactivation).';
