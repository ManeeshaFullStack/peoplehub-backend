-- V25: PostgreSQL row-level security on every tenant-owned table (b2-8 C4; owner decisions O1, O5, O8, O9; Spec D22,
-- D30, 12.1, 15.1). Depends on the tables of V3-V23 and on V24's pre-tenant functions.
--
-- The second tenant boundary. The first stays where it is: every application query is still qualified by the
-- organization taken from the authenticated principal or resolved through V24. This one is enforced by the database
-- for the runtime role, whatever the SQL says.
--
-- Design:
--  * The tenant is the transaction-local setting peoplehub.organization_id, written by the application as each
--    transaction begins (TenantTransactionManager) or right after a public flow resolves its organization
--    (PreTenantResolver). set_config(..., true) only: it ends with the transaction.
--  * peoplehub_current_organization_id() reads it and FAILS LOUDLY when it is missing: unset (NULL), empty ('', which
--    is what PostgreSQL reports in a session after a transaction-local value has ended) or not a UUID. No tenant never
--    means "an empty tenant" (O5). SQLSTATE PH001, so the failure is recognisable.
--  * One policy per table, FOR ALL, for the runtime role: USING and WITH CHECK both require the row's tenant key to
--    equal the bound tenant, so the runtime role can read, change or delete only its own organization's rows, and can
--    neither insert a row for another organization nor move a row to one. organization's tenant key is its id; every
--    other table's is organization_id.
--  * A bound tenant looking up another organization's id finds nothing: an ordinary not-found, never an error.
--  * PostgreSQL evaluates a policy only on the rows a statement examines. A statement that examines a row without a
--    bound tenant raises; one whose index lookup finds no candidate row at all returns nothing, having seen nothing.
--  * No FORCE ROW LEVEL SECURITY (O8): the owner role is the trusted boundary. V24's SECURITY DEFINER functions run
--    as the owner, which is how the narrow pre-tenant steps keep working; nothing else runs as the owner at runtime.
--    The runtime role is neither the owner, a superuser nor BYPASSRLS, and gains nothing here beyond EXECUTE on the
--    helper.
--
-- Inventory (O9). RLS on 13 tables: organization, employee, employee_invitation, refresh_token, mfa_recovery_code,
-- mfa_challenge, session_step_up, organization_verification_token, password_reset_token, audit_log, email_outbox,
-- notification, notification_preference. Deliberately outside: login_attempt (records what was typed, before any
-- organization is known), email_suppression (global by design, b1-4), shedlock and flyway_schema_history
-- (infrastructure).
--
-- Grant hardening carried from C3: registration now creates organizations only through
-- peoplehub_create_organization (V24), so the runtime role's direct INSERT on organization (V8) is revoked.
--
-- PRECONDITION: identical guard to V3-V24 -- the runtime role must already exist.
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

-- ---- The bound tenant, or a loud failure ----
--
-- SECURITY INVOKER: it reads a setting of the caller's own transaction and needs no privilege; it can bypass nothing.
CREATE FUNCTION peoplehub_current_organization_id()
    RETURNS uuid
    LANGUAGE plpgsql
    STABLE
    SECURITY INVOKER
    SET search_path = pg_catalog, pg_temp
AS $fn$
DECLARE
    bound text := current_setting('peoplehub.organization_id', true);
BEGIN
    IF bound IS NULL OR bound = '' THEN
        RAISE EXCEPTION 'No tenant is bound to this transaction' USING ERRCODE = 'PH001';
    END IF;
    IF bound !~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$' THEN
        RAISE EXCEPTION 'The tenant bound to this transaction is not a valid id' USING ERRCODE = 'PH001';
    END IF;
    RETURN bound::uuid;
END
$fn$;

COMMENT ON FUNCTION peoplehub_current_organization_id() IS
    'b2-8 (V25): the organization bound to the current transaction (peoplehub.organization_id). Raises PH001 when it is unset, empty or not a UUID; never answers an empty tenant.';

REVOKE ALL ON FUNCTION peoplehub_current_organization_id() FROM PUBLIC;

-- ---- Policies and enabling ----
DO $mig$
DECLARE
    runtime_role text := '${runtime_role}';
    child text;
BEGIN
    EXECUTE format('GRANT EXECUTE ON FUNCTION peoplehub_current_organization_id() TO %I', runtime_role);

    -- organization: the tenant key is the row's own id.
    EXECUTE format(
        'CREATE POLICY tenant_isolation ON public.organization AS PERMISSIVE FOR ALL TO %I'
        ' USING (id = public.peoplehub_current_organization_id())'
        ' WITH CHECK (id = public.peoplehub_current_organization_id())',
        runtime_role);
    ALTER TABLE public.organization ENABLE ROW LEVEL SECURITY;

    -- Every other tenant-owned table: organization_id.
    FOREACH child IN ARRAY ARRAY[
        'employee',
        'employee_invitation',
        'refresh_token',
        'mfa_recovery_code',
        'mfa_challenge',
        'session_step_up',
        'organization_verification_token',
        'password_reset_token',
        'audit_log',
        'email_outbox',
        'notification',
        'notification_preference'
    ]
    LOOP
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON public.%I AS PERMISSIVE FOR ALL TO %I'
            ' USING (organization_id = public.peoplehub_current_organization_id())'
            ' WITH CHECK (organization_id = public.peoplehub_current_organization_id())',
            child, runtime_role);
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', child);
    END LOOP;

    -- Carried from C3: organizations are created only through peoplehub_create_organization (V24).
    EXECUTE format(
        'REVOKE INSERT (name, login_key_normalized, timezone) ON public.organization FROM %I',
        runtime_role);
END
$mig$;
