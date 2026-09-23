-- V8: organization (b2-1, Spec 2.1.1, 12, 12.1). The tenant root: every subsequent table's
-- organization_id refers here.
--
-- Decisions: CLAUDE.md B2 planning ("B2-1 Implementation Plan" record). organization.id is UUID
-- (matching every existing organization_id column already in the schema: audit_log, email_outbox,
-- notification, notification_preference, email_suppression's deliberate exception aside) -- the
-- FIRST table in this schema whose own primary key is a UUID rather than a BIGINT GENERATED ALWAYS
-- AS IDENTITY, since every prior table's identity was a surrogate row id, not the tenant root
-- itself. login_key_normalized is lower-case + trimmed; the CHECK below enforces that shape in the
-- database, not only in application code, the same "invariants enforced in the database" discipline
-- this repo already applies everywhere else.
--
-- PRECONDITION: identical guard to V3-V7 -- the runtime role must already exist.
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

-- id is database-generated (DEFAULT gen_random_uuid(), built into PostgreSQL core since v13, no
-- extension needed -- unlike V1's btree_gist). Unlike a BIGINT GENERATED ALWAYS AS IDENTITY column,
-- a UUID DEFAULT is not syntax-enforced against an explicit value; the equivalent guarantee for the
-- runtime role is privilege-enforced instead (no INSERT grant on id, below) -- the same effective
-- protection, a different mechanism.
CREATE TABLE organization (
    id                       UUID         NOT NULL DEFAULT gen_random_uuid(),
    name                     VARCHAR(200) NOT NULL,
    login_key_normalized     VARCHAR(200) NOT NULL,
    timezone                 VARCHAR(64)  NOT NULL,
    status                   VARCHAR(24)  NOT NULL DEFAULT 'PENDING_VERIFICATION',
    onboarding_completed_at  TIMESTAMPTZ,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_organization PRIMARY KEY (id),
    CONSTRAINT uq_organization_login_key UNIQUE (login_key_normalized),
    CONSTRAINT ck_organization_name_not_blank
        CHECK (char_length(name) > 0),
    CONSTRAINT ck_organization_login_key_not_blank
        CHECK (char_length(login_key_normalized) > 0),
    CONSTRAINT ck_organization_login_key_is_normalized
        CHECK (login_key_normalized = lower(btrim(login_key_normalized))),
    CONSTRAINT ck_organization_timezone_not_blank
        CHECK (char_length(timezone) > 0),
    CONSTRAINT ck_organization_status
        CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'SUSPENDED', 'CLOSED'))
);

COMMENT ON TABLE organization IS 'Tenant root (Spec 2.1.1, 12, 12.1). Every other tenant-owned table''s organization_id refers here.';
COMMENT ON COLUMN organization.id IS 'Database-generated (gen_random_uuid()); the runtime role has no INSERT privilege on it.';
COMMENT ON COLUMN organization.login_key_normalized IS 'Lower-case, trimmed public login key used to resolve the tenant at login (Spec 2.1.1, 2.1.6). Never accepted from a client as authority after login.';
COMMENT ON COLUMN organization.status IS 'PENDING_VERIFICATION (founder not yet verified) -> ACTIVE -> SUSPENDED/CLOSED.';
COMMENT ON COLUMN organization.created_at IS 'Database time (now()); the application never supplies it.';
COMMENT ON COLUMN organization.updated_at IS 'Set explicitly by the application on every write; the database only supplies its initial value at insert time.';

-- Runtime role privileges: exactly what b2-1 needs, nothing later branches will need until they
-- exist (same "least privilege matches what is actually shipped" discipline as every table since
-- V4).
--  * INSERT on exactly (name, login_key_normalized, timezone): not id (database-generated), not
--    status (defaults to PENDING_VERIFICATION, no code writes it explicitly at creation), not
--    onboarding_completed_at/created_at/updated_at.
--  * UPDATE on (name, timezone, status, onboarding_completed_at, updated_at): not
--    login_key_normalized (renaming a tenant's login key is not designed yet) and not id.
REVOKE ALL ON organization FROM PUBLIC;

DO $mig$
DECLARE
    runtime_role text := '${runtime_role}';
BEGIN
    EXECUTE format('GRANT SELECT ON organization TO %I', runtime_role);
    EXECUTE format(
        'GRANT INSERT (name, login_key_normalized, timezone) ON organization TO %I',
        runtime_role);
    EXECUTE format(
        'GRANT UPDATE (name, timezone, status, onboarding_completed_at, updated_at)'
        ' ON organization TO %I',
        runtime_role);
END
$mig$;
