-- V9: employee (b2-1, Spec 3.2, 12, 12.1). Depends on organization (V8).
--
-- Decisions: CLAUDE.md B2 planning ("B2-1 Implementation Plan" record, corrected).
--  * employee_code is admin/import-supplied (never database-generated), required, and immutable --
--    immutability is privilege-enforced: the runtime role's UPDATE grant on this table never
--    includes employee_code (below), the same "enforced by grant, not by trust" discipline B0-6/12
--    already applies to audit_log's id/occurred_at.
--  * mfa_totp_secret is application-level envelope-encrypted ciphertext, never plaintext. The
--    column only commits to the storage shape (VARCHAR(512), comfortably over an encrypted TOTP
--    seed's size); the encryption mechanism itself (key source, algorithm) is b2-7's concern, not
--    this migration's -- nothing in b2-1 writes to this column.
--  * role is a single enum-shaped column, not a separate role table (Spec D25's "relation, not a
--    separate identity" describes Employee/Admin/Super Admin being one row, not the role value's
--    own storage).
--  * default_approver_id self-references employee(id); PostgreSQL allows a self-referencing foreign
--    key declared inline in the same CREATE TABLE, since the constraint is only checked once the
--    table exists in the catalog.
--  * FLAGGED SPEC INCONSISTENCY (not silently resolved): Spec 12 lists employee.status as
--    INVITED/ACTIVE/DEACTIVATED/DELETED_TOMBSTONE, but Spec 2.1.3 step 3 says the founder's
--    employee row is created with status = PENDING_VERIFICATION. The CHECK below takes the safe
--    superset (adds PENDING_VERIFICATION as a fifth accepted value) so neither reading is
--    foreclosed; this needs the owner's explicit confirmation before merge (CLAUDE.md 1.4).
--
-- PRECONDITION: identical guard to V3-V8 -- the runtime role must already exist.
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

-- Unlike audit_log/email_outbox/notification's organization_id (no FK until B2), employee is
-- itself part of B2, so organization_id gets a REAL foreign key from the start -- there is no
-- "forward-compatible, no FK yet" period for this table. The nil-UUID CHECK pattern those earlier
-- tables needed (to reject a fabricated placeholder in the absence of a FK) is not repeated here:
-- a real FK already rejects any organization_id that is not a genuine organization.id, nil UUID
-- included, since organization.id is itself database-generated and can never legitimately be nil.
CREATE TABLE employee (
    id                   UUID         NOT NULL DEFAULT gen_random_uuid(),
    organization_id      UUID         NOT NULL,
    department_id        UUID,
    employee_code        VARCHAR(64)  NOT NULL,
    name                 VARCHAR(200) NOT NULL,
    email                VARCHAR(254) NOT NULL,
    email_normalized     VARCHAR(254) NOT NULL,
    password_hash        VARCHAR(255),
    status               VARCHAR(24)  NOT NULL DEFAULT 'INVITED',
    role                 VARCHAR(16)  NOT NULL,
    default_approver_id  UUID,
    mfa_enabled          BOOLEAN      NOT NULL DEFAULT false,
    mfa_totp_secret      VARCHAR(512),
    join_date            DATE,
    exit_date            DATE,
    welcome_seen_at      TIMESTAMPTZ,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_employee PRIMARY KEY (id),
    CONSTRAINT fk_employee_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_employee_default_approver
        FOREIGN KEY (default_approver_id) REFERENCES employee (id),
    CONSTRAINT uq_employee_organization_email UNIQUE (organization_id, email_normalized),
    CONSTRAINT uq_employee_organization_code UNIQUE (organization_id, employee_code),
    CONSTRAINT ck_employee_employee_code_not_blank
        CHECK (char_length(employee_code) > 0),
    CONSTRAINT ck_employee_name_not_blank
        CHECK (char_length(name) > 0),
    CONSTRAINT ck_employee_email_not_blank
        CHECK (char_length(email) > 0),
    CONSTRAINT ck_employee_email_normalized_not_blank
        CHECK (char_length(email_normalized) > 0),
    CONSTRAINT ck_employee_email_normalized_is_normalized
        CHECK (email_normalized = lower(btrim(email_normalized))),
    -- PENDING_VERIFICATION included pending the owner's confirmation -- see the flagged
    -- inconsistency above.
    CONSTRAINT ck_employee_status
        CHECK (status IN
            ('PENDING_VERIFICATION', 'INVITED', 'ACTIVE', 'DEACTIVATED', 'DELETED_TOMBSTONE')),
    CONSTRAINT ck_employee_role
        CHECK (role IN ('SUPER_ADMIN', 'ADMIN', 'EMPLOYEE'))
);

COMMENT ON TABLE employee IS 'Every Super Admin/Admin/Employee is one row here (Spec 3.2: role is a relation, not a separate identity).';
COMMENT ON COLUMN employee.id IS 'Database-generated (gen_random_uuid()); the runtime role has no INSERT privilege on it.';
COMMENT ON COLUMN employee.department_id IS 'No FK yet: the department table arrives in B3.';
COMMENT ON COLUMN employee.employee_code IS 'Admin/import-supplied, never database-generated. Immutable: the runtime role has no UPDATE privilege on this column at all.';
COMMENT ON COLUMN employee.mfa_totp_secret IS 'Application-level envelope-encrypted ciphertext ONLY. Never plaintext. Key management is b2-7''s concern; nothing in b2-1 writes this column.';
COMMENT ON COLUMN employee.status IS 'Spec 12 vs Spec 2.1.3 flagged inconsistency: PENDING_VERIFICATION added as a safe superset pending owner confirmation.';
COMMENT ON COLUMN employee.created_at IS 'Database time (now()); the application never supplies it.';
COMMENT ON COLUMN employee.updated_at IS 'Set explicitly by the application on every write; the database only supplies its initial value at insert time.';

-- Runtime role privileges: exactly what b2-1 needs. Narrow UPDATE grants split by use case, the
-- same discipline email_outbox (V4/V5) and notification (V6) already apply, rather than one broad
-- UPDATE on the whole row. employee_code is deliberately absent from every UPDATE grant (immutable
-- by privilege, not just convention). role and email/email_normalized are also absent: no b2-1 code
-- path changes them, so the grant waits for the branch that actually needs it (same "grant matches
-- what is shipped, not what will eventually be needed" rule as email_outbox's V4-to-V5 history).
REVOKE ALL ON employee FROM PUBLIC;

DO $mig$
DECLARE
    runtime_role text := '${runtime_role}';
BEGIN
    EXECUTE format('GRANT SELECT ON employee TO %I', runtime_role);
    EXECUTE format(
        'GRANT INSERT (organization_id, department_id, employee_code, name, email,'
        ' email_normalized, status, role, join_date) ON employee TO %I',
        runtime_role);
    EXECUTE format(
        'GRANT UPDATE (password_hash, mfa_enabled, mfa_totp_secret) ON employee TO %I',
        runtime_role);
    EXECUTE format('GRANT UPDATE (status, exit_date) ON employee TO %I', runtime_role);
    EXECUTE format('GRANT UPDATE (welcome_seen_at) ON employee TO %I', runtime_role);
    EXECUTE format(
        'GRANT UPDATE (department_id, name, default_approver_id, updated_at) ON employee TO %I',
        runtime_role);
END
$mig$;
