-- V12: adds the organization_id foreign key to the four tables built before organization existed
-- (b2-1, Spec 2.1.1, 12.1). Resolves B0-6/1's own documented plan ("B2 adds the FK") and CLAUDE.md
-- section 15 item 13.
--
-- This is additive, not destructive: no new column, no data rewritten. If any row's organization_id
-- does not resolve to a real organization.id, this migration fails outright (a foreign key cannot be
-- added over violating data) -- which is correct: no application path has ever written a fabricated
-- organization id to any of these four tables (B0-6/1, V4/V6's own writers both refuse null and the
-- nil UUID, and until this migration nothing could legitimately resolve a real one), so a failure
-- here would mean a real data problem worth surfacing, not something to work around.
--
-- Grants are unchanged: adding a foreign key needs no new privilege on either table.
--
-- No runtime_role guard needed: this migration does not touch the runtime role at all.

ALTER TABLE audit_log
    ADD CONSTRAINT fk_audit_log_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id);

ALTER TABLE email_outbox
    ADD CONSTRAINT fk_email_outbox_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id);

ALTER TABLE notification
    ADD CONSTRAINT fk_notification_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id);

ALTER TABLE notification_preference
    ADD CONSTRAINT fk_notification_preference_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id);

COMMENT ON CONSTRAINT fk_audit_log_organization ON audit_log IS 'Added b2-1 (V12): the organization table did not exist when V3 created this column (B0-6/1).';
COMMENT ON CONSTRAINT fk_email_outbox_organization ON email_outbox IS 'Added b2-1 (V12): the organization table did not exist when V4 created this column.';
COMMENT ON CONSTRAINT fk_notification_organization ON notification IS 'Added b2-1 (V12): the organization table did not exist when V6 created this column.';
COMMENT ON CONSTRAINT fk_notification_preference_organization ON notification_preference IS 'Added b2-1 (V12): the organization table did not exist when V6 created this column.';

-- email_suppression (V7) is deliberately NOT touched: it has no organization_id at all, by design
-- (b1-4 decision, global suppression list). notification.employee_id and
-- notification_preference.employee_id are also not touched: an employee-id FK retrofit was not part
-- of this plan and stays a deliberate, separate follow-up rather than unrelated scope creep here.
