-- V15: employee_invitation hardening (b2-4, B2-4/O6; Spec 2.1.5, 12.1). Depends on employee_invitation (V10) and
-- employee's UNIQUE (organization_id, id) (V14).
--
-- Decisions: CLAUDE.md "B2-4 decisions" (O6). Three invariants move into the database before b2-4 starts writing
-- invitations:
--  * token_hash is UNIQUE (V10 only indexed it): an invitation is looked up by its token's hash, so two rows can never
--    answer to the same token. The unique constraint's index replaces V10's plain idx_employee_invitation_token_hash,
--    which is dropped as redundant. Invitation token hashes are SHA-256 of 256 random bits: a real collision is not
--    expected, the constraint makes one impossible rather than unlikely.
--  * An invitation is never both consumed and revoked: those are two different, final outcomes.
--  * The inviter belongs to the invitation's organization: a composite foreign key
--    (organization_id, inviter_employee_id) -> employee (organization_id, id), the same tenant-safe pattern as V14's
--    refresh_token key (Spec 12.1). V10's plain fk_employee_invitation_inviter stays (forward-only; redundant, not
--    wrong).
--
-- Nothing has written employee_invitation before b2-4, so no existing row is expected to violate these. If one does,
-- the migration fails outright, which is correct (the same reasoning as V12).
--
-- Grants are unchanged: constraints need no new privilege. No runtime_role guard needed: this migration does not touch
-- the runtime role.

ALTER TABLE employee_invitation
    ADD CONSTRAINT uq_employee_invitation_token_hash UNIQUE (token_hash);

DROP INDEX idx_employee_invitation_token_hash;

ALTER TABLE employee_invitation
    ADD CONSTRAINT ck_employee_invitation_not_consumed_and_revoked
        CHECK (consumed_at IS NULL OR revoked_at IS NULL),
    ADD CONSTRAINT fk_employee_invitation_inviter_in_organization
        FOREIGN KEY (organization_id, inviter_employee_id) REFERENCES employee (organization_id, id);

COMMENT ON CONSTRAINT uq_employee_invitation_token_hash ON employee_invitation IS 'b2-4 (V15): one invitation per token; replaces V10''s plain token_hash index.';
COMMENT ON CONSTRAINT ck_employee_invitation_not_consumed_and_revoked ON employee_invitation IS 'b2-4 (V15): accepted and revoked are different final outcomes.';
COMMENT ON CONSTRAINT fk_employee_invitation_inviter_in_organization ON employee_invitation IS 'b2-4 (V15): the inviter is an employee of the same organization.';
