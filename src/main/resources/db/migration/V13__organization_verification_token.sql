-- V13: organization_verification_token (b2-2, Spec 2.1.3). Depends on organization (V8).
--
-- Decisions: CLAUDE.md B2-2 decisions (B2-2/5). One raw token is generated and emailed to the
-- founder; only its hash is ever stored, the same discipline employee_invitation.token_hash and
-- refresh_token.token_hash (V10/V11) already apply. Single-use, enforced by consumed_at plus the
-- application's atomic "UPDATE ... WHERE consumed_at IS NULL" consume step (RegistrationService),
-- not by a database constraint alone: a second consume attempt after the first succeeds is a normal,
-- expected race (a resend + the original link both landing), not a data error.
--
-- Unlike audit_log/email_outbox/notification's organization_id (no FK until B2), this table is
-- itself part of B2 and organization already exists by the time it is created, so it gets a real
-- foreign key from the start -- same reasoning as employee's organization_id (V9).
--
-- PRECONDITION: identical guard to V3-V12 -- the runtime role must already exist.
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

CREATE TABLE organization_verification_token (
    id               UUID         NOT NULL DEFAULT gen_random_uuid(),
    organization_id  UUID         NOT NULL,
    token_hash       VARCHAR(255) NOT NULL,
    expires_at       TIMESTAMPTZ  NOT NULL,
    consumed_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_organization_verification_token PRIMARY KEY (id),
    CONSTRAINT fk_organization_verification_token_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT uq_organization_verification_token_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_organization_verification_token_token_hash_not_blank
        CHECK (char_length(token_hash) > 0)
);

CREATE INDEX idx_organization_verification_token_organization_id
    ON organization_verification_token (organization_id);

COMMENT ON TABLE organization_verification_token IS 'Single-use founder email verification tokens (Spec 2.1.3, B2-2/5). Hashed at rest; the raw token is emailed once and never stored.';
COMMENT ON COLUMN organization_verification_token.id IS 'Database-generated (gen_random_uuid()); the runtime role has no INSERT privilege on it.';
COMMENT ON COLUMN organization_verification_token.token_hash IS 'Hashed at rest; the raw token value is never stored, same discipline as employee_invitation.token_hash and refresh_token.token_hash.';
COMMENT ON COLUMN organization_verification_token.consumed_at IS 'NULL until used. Set once, by the application''s atomic UPDATE ... WHERE consumed_at IS NULL (single-use enforcement), not by a database constraint.';
COMMENT ON COLUMN organization_verification_token.created_at IS 'Database time (now()); the application never supplies it.';

-- Runtime role privileges: exactly what b2-2 needs, the same "narrow grant matches what is shipped"
-- discipline as every table since V4. Not id/created_at (database-generated). Not organization_id
-- or token_hash on UPDATE: a token's organization and value never change after creation, only
-- whether it has been consumed.
REVOKE ALL ON organization_verification_token FROM PUBLIC;

DO $mig$
DECLARE
    runtime_role text := '${runtime_role}';
BEGIN
    EXECUTE format('GRANT SELECT ON organization_verification_token TO %I', runtime_role);
    EXECUTE format(
        'GRANT INSERT (organization_id, token_hash, expires_at)'
        ' ON organization_verification_token TO %I',
        runtime_role);
    EXECUTE format(
        'GRANT UPDATE (consumed_at) ON organization_verification_token TO %I', runtime_role);
END
$mig$;
