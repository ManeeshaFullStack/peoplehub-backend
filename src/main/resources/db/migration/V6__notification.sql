-- V6: in-app notifications + preferences (b1-3, Spec 9, 12, 12.1).
--
-- Decisions: CLAUDE.md, "B1-3 decisions" (see the b1-3 planning record). Same forward-compatible
-- tenant pattern as email_outbox (V4): organization_id and employee_id are UUID identifiers with no
-- foreign key, because neither the organization nor the employee table exists until B2. Unlike
-- email_outbox, this migration indexes both tables from the start: B1-3 is itself the reader
-- (the notification list and the SSE unread bell), so there is no reason to defer indexing the way
-- b1-1 deferred email_outbox's until b1-2 became its reader.
--
-- PRECONDITION: identical guard to V3-V5 -- the runtime role must already exist.
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

-- Mutable, like email_outbox: `read` toggles, so this table gets no append-only trigger. Only
-- audit_log is immutable.
CREATE TABLE notification (
    id              BIGINT       GENERATED ALWAYS AS IDENTITY,
    organization_id UUID         NOT NULL,
    employee_id     UUID         NOT NULL,
    type            VARCHAR(64)  NOT NULL,
    payload         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    read            BOOLEAN      NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_notification PRIMARY KEY (id),
    CONSTRAINT ck_notification_organization_not_nil
        CHECK (organization_id <> '00000000-0000-0000-0000-000000000000'),
    CONSTRAINT ck_notification_employee_not_nil
        CHECK (employee_id <> '00000000-0000-0000-0000-000000000000'),
    CONSTRAINT ck_notification_type
        CHECK (type ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT ck_notification_payload
        CHECK (jsonb_typeof(payload) = 'object' AND octet_length(payload::text) <= 4096)
);

COMMENT ON TABLE notification IS 'In-app notifications, mirroring email events (Spec 9.1, 9.2). No FK until B2 creates organization/employee tables.';
COMMENT ON COLUMN notification.organization_id IS 'Tenant. No FK until B2.';
COMMENT ON COLUMN notification.employee_id IS 'Recipient. No FK until B2 (no employee table yet).';
COMMENT ON COLUMN notification.payload IS 'Versioned structured data {"v":1,"attributes":{}}; see NotificationPayload. No secrets, no free text.';
COMMENT ON COLUMN notification.created_at IS 'Database time (now()); the application never supplies it.';

-- Supports the list query (Spec 13.1: notifications sort by timestamp descending).
CREATE INDEX idx_notification_employee_created
    ON notification (organization_id, employee_id, created_at DESC);

-- Supports the unread-count the SSE bell needs (Spec 9.2): only unread rows are ever scanned this
-- way, so only unread rows are indexed for it.
CREATE INDEX idx_notification_unread
    ON notification (organization_id, employee_id)
    WHERE read = false;

-- One row per (organization, employee, type): a natural composite key, so no surrogate id and no
-- separate uniqueness constraint to keep in step with it.
--
-- Security- and approval-critical types cannot be fully disabled (Spec 9.3). Enforced in the
-- database too, not only in NotificationPreferenceService: a CHECK naming the current critical
-- types directly, mirroring the "invariants enforced in the database" rule this repo already
-- follows for other tables. CriticalNotificationTypesTest keeps this list and the Java constant
-- (CriticalNotificationTypes) in step, the same discipline AuditLogMigrationTest already applies to
-- ActorId/CorrelationId. Extending the list is a forward-only migration plus a one-line Java change,
-- never an edit to this file.
CREATE TABLE notification_preference (
    organization_id UUID         NOT NULL,
    employee_id     UUID         NOT NULL,
    type            VARCHAR(64)  NOT NULL,
    email           BOOLEAN      NOT NULL DEFAULT true,
    in_app          BOOLEAN      NOT NULL DEFAULT true,
    CONSTRAINT pk_notification_preference PRIMARY KEY (organization_id, employee_id, type),
    CONSTRAINT ck_notification_preference_organization_not_nil
        CHECK (organization_id <> '00000000-0000-0000-0000-000000000000'),
    CONSTRAINT ck_notification_preference_employee_not_nil
        CHECK (employee_id <> '00000000-0000-0000-0000-000000000000'),
    CONSTRAINT ck_notification_preference_type
        CHECK (type ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT ck_notification_preference_critical_always_on
        CHECK (type NOT IN ('PASSWORD_RESET', 'NEW_DEVICE_PAIRED', 'EMPLOYEE_INVITED')
            OR (email AND in_app))
);

COMMENT ON TABLE notification_preference IS 'Per-employee, per-type email/in-app toggles (Spec 9.3). Security-/approval-critical types cannot be disabled; see ck_notification_preference_critical_always_on and CriticalNotificationTypes.';

-- Runtime role privileges: exactly what b1-3 needs, table by table, no ALTER DEFAULT PRIVILEGES.
--  * notification: SELECT (the test-only controller lists/streams); INSERT on the four columns the
--    writer supplies (not id/created_at, database-generated); UPDATE on read only (mark-read).
--  * notification_preference: SELECT, INSERT (all five columns: a preference row's identity and
--    initial toggles are written together), UPDATE on email/in_app only (the three key columns are
--    the primary key and are never updated in place).
REVOKE ALL ON notification FROM PUBLIC;
REVOKE ALL ON notification_preference FROM PUBLIC;

DO $mig$
DECLARE
    runtime_role text := '${runtime_role}';
BEGIN
    EXECUTE format('GRANT SELECT ON notification TO %I', runtime_role);
    EXECUTE format(
        'GRANT INSERT (organization_id, employee_id, type, payload) ON notification TO %I',
        runtime_role);
    EXECUTE format('GRANT UPDATE (read) ON notification TO %I', runtime_role);

    EXECUTE format('GRANT SELECT ON notification_preference TO %I', runtime_role);
    EXECUTE format(
        'GRANT INSERT (organization_id, employee_id, type, email, in_app) ON notification_preference TO %I',
        runtime_role);
    EXECUTE format('GRANT UPDATE (email, in_app) ON notification_preference TO %I', runtime_role);
END
$mig$;
