-- V24: owner-defined functions for the flows that must find their organization before they have one (b2-8 C3; owner
-- decisions O3, O4, O6; Spec D22, D30, 12.1, 15.1). Depends on organization (V8), employee_invitation (V10, V15),
-- refresh_token (V11), organization_verification_token (V13), password_reset_token (V17), mfa_challenge (V21) and
-- email_outbox (V4, V5, V12).
--
-- Why: V25 (b2-8 C4) turns on row-level security, after which the runtime role sees a tenant-owned row only inside a
-- transaction bound to that row's organization. A public flow (login, a token in an email link, the outbox worker)
-- has no organization yet. Each one first calls exactly one of these functions to learn it, binds the transaction to
-- it, and only then reads or writes tenant rows through its ordinary, organization-qualified SQL. RLS is NOT enabled
-- here; these functions change nothing until V25.
--
-- Hardening, the same for every function:
--  * SECURITY DEFINER, owned by the migration/owner role, which owns the tables. The owner is the trusted boundary and
--    is not subject to RLS without FORCE (O8), so these functions keep working once V25 is in place.
--  * SET search_path = pg_catalog, pg_temp, and every object schema-qualified: a caller cannot redirect a name to an
--    object of its own.
--  * The narrowest answer: a resolver returns one organization id or NULL, nothing from the row. The organization
--    function returns only the new id. The outbox discovery returns only row ids with their organization ids.
--  * No way to list or search: every resolver needs an exact login key or an exact token hash (a SHA-256 of a
--    256-bit random token), and matches through a UNIQUE constraint, so it can answer at most one organization.
--  * STRICT: a NULL argument answers NULL without running.
--  * EXECUTE is revoked from PUBLIC and granted to the runtime role alone.
--
-- These functions are not an RLS bypass: none returns tenant data, and none takes an organization id it would then act
-- on. Everything else a flow does to tenant rows still goes through the runtime role, under its table grants and,
-- from V25, under the policies.
--
-- PRECONDITION: identical guard to V3-V23 -- the runtime role must already exist.
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

-- ---- Login key -> organization (login, forgot password, resend verification) ----

CREATE FUNCTION peoplehub_organization_by_login_key(p_login_key_normalized text)
    RETURNS uuid
    LANGUAGE sql
    STABLE
    STRICT
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $fn$
    SELECT o.id FROM public.organization o WHERE o.login_key_normalized = p_login_key_normalized
$fn$;

COMMENT ON FUNCTION peoplehub_organization_by_login_key(text) IS
    'b2-8 (V24): the id of the organization with this exact normalized login key, or NULL. Any status: the caller decides what the status means, inside the organization.';

-- ---- Token hash -> organization (one function per token table) ----

CREATE FUNCTION peoplehub_organization_by_refresh_token(p_token_hash text)
    RETURNS uuid
    LANGUAGE sql
    STABLE
    STRICT
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $fn$
    SELECT t.organization_id FROM public.refresh_token t WHERE t.token_hash = p_token_hash
$fn$;

COMMENT ON FUNCTION peoplehub_organization_by_refresh_token(text) IS
    'b2-8 (V24): refresh and logout. The organization of the refresh token with this hash, or NULL, whatever the token''s state.';

CREATE FUNCTION peoplehub_organization_by_verification_token(p_token_hash text)
    RETURNS uuid
    LANGUAGE sql
    STABLE
    STRICT
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $fn$
    SELECT t.organization_id FROM public.organization_verification_token t WHERE t.token_hash = p_token_hash
$fn$;

COMMENT ON FUNCTION peoplehub_organization_by_verification_token(text) IS
    'b2-8 (V24): founder email verification. The organization of the verification token with this hash, or NULL, whatever the token''s state.';

CREATE FUNCTION peoplehub_organization_by_password_reset_token(p_token_hash text)
    RETURNS uuid
    LANGUAGE sql
    STABLE
    STRICT
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $fn$
    SELECT t.organization_id FROM public.password_reset_token t WHERE t.token_hash = p_token_hash
$fn$;

COMMENT ON FUNCTION peoplehub_organization_by_password_reset_token(text) IS
    'b2-8 (V24): password reset. The organization of the reset code with this hash, or NULL, whatever the code''s state.';

CREATE FUNCTION peoplehub_organization_by_invitation_token(p_token_hash text)
    RETURNS uuid
    LANGUAGE sql
    STABLE
    STRICT
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $fn$
    SELECT i.organization_id FROM public.employee_invitation i WHERE i.token_hash = p_token_hash
$fn$;

COMMENT ON FUNCTION peoplehub_organization_by_invitation_token(text) IS
    'b2-8 (V24): invitation preview and acceptance. The organization of the invitation with this token hash, or NULL, whatever the invitation''s state.';

CREATE FUNCTION peoplehub_organization_by_mfa_challenge(p_token_hash text)
    RETURNS uuid
    LANGUAGE sql
    STABLE
    STRICT
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $fn$
    SELECT c.organization_id FROM public.mfa_challenge c WHERE c.token_hash = p_token_hash
$fn$;

COMMENT ON FUNCTION peoplehub_organization_by_mfa_challenge(text) IS
    'b2-8 (V24): the MFA step of a sign-in. The organization of the challenge with this token hash, or NULL, whatever the challenge''s state.';

-- ---- Organization creation (registration, O6) ----
--
-- A new organization has no tenant yet, so it cannot be inserted under a tenant-bound policy. This function inserts
-- exactly the three columns registration supplies; the id (gen_random_uuid()), the status (PENDING_VERIFICATION) and
-- the timestamps stay the database's defaults, and the table's CHECK and UNIQUE constraints apply unchanged: a taken
-- login key raises the ordinary unique violation (SQLSTATE 23505) for the caller to translate. It returns only the new
-- id, which registration then binds its transaction to.
CREATE FUNCTION peoplehub_create_organization(p_name text, p_login_key_normalized text, p_timezone text)
    RETURNS uuid
    LANGUAGE sql
    VOLATILE
    STRICT
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $fn$
    INSERT INTO public.organization (name, login_key_normalized, timezone)
    VALUES (p_name, p_login_key_normalized, p_timezone)
    RETURNING id
$fn$;

COMMENT ON FUNCTION peoplehub_create_organization(text, text, text) IS
    'b2-8 (V24): registration. Inserts a PENDING_VERIFICATION organization with a database-generated id and returns only that id.';

-- ---- Email outbox, across organizations (the scheduled worker, O4) ----
--
-- The worker has no tenant: it must find due work in every organization. These two statements are the only
-- cross-tenant steps it takes; each row it then claims, loads, sends and records is handled in a transaction bound to
-- that row's organization. email_suppression is global by design (b1-4) and needs nothing here.
--
-- Time is the database's own (now()), never a caller's: the same clock that stamps a claim (last_attempt_at = now()).
-- A caller therefore cannot make a row that another worker is sending look stale, nor a retry scheduled for later
-- look due, by passing a time from the future.

-- A row left SENDING by a worker that died mid-send becomes RETRYING again, once its claim is older than p_stale_after
-- by the database's clock. p_stale_after is the worker's configured stale-claim age, bounded to 1 minute - 1 day, so a
-- caller cannot shrink it to reclaim messages that are still being sent. Only the status changes; returns how many.
CREATE FUNCTION peoplehub_email_outbox_reclaim_stale(p_stale_after interval)
    RETURNS integer
    LANGUAGE plpgsql
    VOLATILE
    STRICT
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $fn$
DECLARE
    reclaimed integer;
BEGIN
    IF p_stale_after < interval '1 minute' OR p_stale_after > interval '1 day' THEN
        RAISE EXCEPTION 'p_stale_after must be between 1 minute and 1 day' USING ERRCODE = 'invalid_parameter_value';
    END IF;
    UPDATE public.email_outbox
       SET status = 'RETRYING'
     WHERE status = 'SENDING'
       AND last_attempt_at < now() - p_stale_after;
    GET DIAGNOSTICS reclaimed = ROW_COUNT;
    RETURN reclaimed;
END
$fn$;

COMMENT ON FUNCTION peoplehub_email_outbox_reclaim_stale(interval) IS
    'b2-8 (V24): outbox worker. Sets SENDING rows whose claim is older than p_stale_after (1 minute to 1 day) by the database clock back to RETRYING; returns the count.';

-- The rows due now by the database's clock, oldest first, at most p_limit (1 to 1000): only each row's id and
-- organization id, never its recipient, type or payload.
CREATE FUNCTION peoplehub_email_outbox_due(p_limit integer)
    RETURNS TABLE (id bigint, organization_id uuid)
    LANGUAGE plpgsql
    STABLE
    STRICT
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $fn$
BEGIN
    IF p_limit < 1 OR p_limit > 1000 THEN
        RAISE EXCEPTION 'p_limit must be between 1 and 1000' USING ERRCODE = 'invalid_parameter_value';
    END IF;
    RETURN QUERY
        SELECT o.id, o.organization_id
          FROM public.email_outbox o
         WHERE o.status IN ('PENDING', 'RETRYING')
           AND (o.next_attempt_at IS NULL OR o.next_attempt_at <= now())
         ORDER BY o.id
         LIMIT p_limit;
END
$fn$;

COMMENT ON FUNCTION peoplehub_email_outbox_due(integer) IS
    'b2-8 (V24): outbox worker. Ids and organization ids of rows due now by the database clock, oldest first, at most p_limit (1-1000).';

-- ---- Execution: the runtime role only ----
--
-- PostgreSQL grants EXECUTE on a new function to PUBLIC; take it away, then give it to the runtime role alone.
REVOKE ALL ON FUNCTION peoplehub_organization_by_login_key(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION peoplehub_organization_by_refresh_token(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION peoplehub_organization_by_verification_token(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION peoplehub_organization_by_password_reset_token(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION peoplehub_organization_by_invitation_token(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION peoplehub_organization_by_mfa_challenge(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION peoplehub_create_organization(text, text, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION peoplehub_email_outbox_reclaim_stale(interval) FROM PUBLIC;
REVOKE ALL ON FUNCTION peoplehub_email_outbox_due(integer) FROM PUBLIC;

DO $mig$
DECLARE
    runtime_role text := '${runtime_role}';
BEGIN
    EXECUTE format('GRANT EXECUTE ON FUNCTION peoplehub_organization_by_login_key(text) TO %I', runtime_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION peoplehub_organization_by_refresh_token(text) TO %I', runtime_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION peoplehub_organization_by_verification_token(text) TO %I', runtime_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION peoplehub_organization_by_password_reset_token(text) TO %I', runtime_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION peoplehub_organization_by_invitation_token(text) TO %I', runtime_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION peoplehub_organization_by_mfa_challenge(text) TO %I', runtime_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION peoplehub_create_organization(text, text, text) TO %I', runtime_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION peoplehub_email_outbox_reclaim_stale(interval) TO %I', runtime_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION peoplehub_email_outbox_due(integer) TO %I', runtime_role);
END
$mig$;
