-- V7: global email suppression list (b1-4, Spec 9.2, 12).
--
-- Decisions: CLAUDE.md, "B1-4 decisions" (see the b1-4 planning record). Deliberately NOT
-- organization-scoped: a bounced or complained-about address is bad for every tenant, not just the
-- one that happened to trigger the bounce, so this table has no organization_id at all -- a
-- deliberate departure from email_outbox's and notification's forward-compatible per-tenant pattern.
-- There is nothing to make forward compatible here: the identity is the email address itself, not a
-- tenant relationship.
--
-- PRECONDITION: identical guard to V3-V6 -- the runtime role must already exist.
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

-- email is the primary key: one row per suppressed address, global. "since" is the FIRST time it was
-- suppressed -- ON CONFLICT DO NOTHING (EmailOutboxProcessor/EmailWebhookController) keeps the
-- earliest record rather than overwriting it on a later, redundant report.
CREATE TABLE email_suppression (
    email  VARCHAR(254) NOT NULL,
    reason VARCHAR(32)  NOT NULL,
    since  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_email_suppression PRIMARY KEY (email),
    CONSTRAINT ck_email_suppression_email_not_blank
        CHECK (char_length(email) > 0),
    -- Kept in step with SuppressionReason by EmailSuppressionMigrationTest, the same "Java and SQL
    -- agree" discipline AuditLogMigrationTest already applies to ActorId/CorrelationId.
    CONSTRAINT ck_email_suppression_reason
        CHECK (reason IN ('BOUNCE', 'COMPLAINT', 'ADDRESS_REJECTED'))
);

COMMENT ON TABLE email_suppression IS 'Global suppression list (Spec 9.2, 12): addresses that bounced, complained, or were rejected as invalid, across every organization. No organization_id by design.';
COMMENT ON COLUMN email_suppression.reason IS 'SuppressionReason: BOUNCE/COMPLAINT come from the webhook, ADDRESS_REJECTED from a synchronous SMTP-time rejection in EmailOutboxProcessor.';
COMMENT ON COLUMN email_suppression.since IS 'Database time (now()) of the FIRST suppression; the application never supplies it, and a later duplicate is a no-op.';

-- Runtime role: SELECT (the suppression-gate check) and INSERT on exactly (email, reason) -- not
-- "since", database-generated. No UPDATE or DELETE: nothing in b1-4 ever un-suppresses an address: a
-- future Admin-facing "review and clear" capability is a later, separately designed phase, the same
-- way audit_log's own immutability leaves retention/anonymization as a deliberately separate,
-- future decision rather than something bolted on early.
REVOKE ALL ON email_suppression FROM PUBLIC;

DO $mig$
DECLARE
    runtime_role text := '${runtime_role}';
BEGIN
    EXECUTE format('GRANT SELECT ON email_suppression TO %I', runtime_role);
    EXECUTE format('GRANT INSERT (email, reason) ON email_suppression TO %I', runtime_role);
END
$mig$;
