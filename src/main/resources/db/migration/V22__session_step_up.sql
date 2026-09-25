-- V22: session_step_up (b2-7, B2-7/14, B2-7/15, B2-7/16; Spec 8.3). Depends on employee (V9, V14).
--
-- Decisions: CLAUDE.md "B2-7 decisions".
--  * A successful step-up is recorded against the calling session (the refresh-token family id, the access token's
--    sid), not as a token claim: it can never outlive the session and needs no new access token. session_id has no
--    foreign key because refresh_token.family_id is not unique; a step-up of an ended session is useless anyway, since
--    every request re-checks that its session is still active (B2-3/14).
--  * verified_at is the database's time and the runtime role cannot supply it (B2-7/16). The application treats a
--    step-up as fresh for five minutes from it.
--  * Insert-only: the runtime role has SELECT and column-level INSERT, never UPDATE, DELETE or TRUNCATE.
--
-- PRECONDITION: identical guard to V3-V21 -- the runtime role must already exist.
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

CREATE TABLE session_step_up (
    id               BIGINT       GENERATED ALWAYS AS IDENTITY,
    organization_id  UUID         NOT NULL,
    employee_id      UUID         NOT NULL,
    session_id       UUID         NOT NULL,
    method           VARCHAR(32)  NOT NULL,
    verified_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_session_step_up PRIMARY KEY (id),
    CONSTRAINT fk_session_step_up_employee_in_organization
        FOREIGN KEY (organization_id, employee_id) REFERENCES employee (organization_id, id),
    CONSTRAINT ck_session_step_up_method
        CHECK (method IN ('PASSWORD', 'PASSWORD_AND_TOTP', 'PASSWORD_AND_RECOVERY_CODE'))
);

CREATE INDEX idx_session_step_up_session
    ON session_step_up (organization_id, session_id, verified_at);

COMMENT ON TABLE session_step_up IS 'b2-7 (V22): successful step-up verifications, each bound to one session (Spec 8.3). Insert-only for the runtime role.';
COMMENT ON COLUMN session_step_up.session_id IS 'The refresh-token family id (the access token''s sid). No FK: family_id is not unique.';
COMMENT ON COLUMN session_step_up.method IS 'PASSWORD (MFA neither enrolled nor required), PASSWORD_AND_TOTP or PASSWORD_AND_RECOVERY_CODE.';
COMMENT ON COLUMN session_step_up.verified_at IS 'Database time (now()); the runtime role cannot supply it. Fresh for five minutes.';

REVOKE ALL ON session_step_up FROM PUBLIC;

DO $mig$
DECLARE
    runtime_role text := '${runtime_role}';
BEGIN
    EXECUTE format('GRANT SELECT ON session_step_up TO %I', runtime_role);
    EXECUTE format(
        'GRANT INSERT (organization_id, employee_id, session_id, method) ON session_step_up TO %I',
        runtime_role);
END
$mig$;
