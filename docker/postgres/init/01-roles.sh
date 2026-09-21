#!/bin/sh
# Provisions the two application database roles (Spec 15; CLAUDE.md "B0-6 decisions", B0-6/3).
#
# Flyway never creates roles, and migration V3 fails if the runtime role is missing, so the roles must exist before
# the backend first starts. This script does that for the development stack.
#
# The postgres image runs it ONCE, on an empty data directory, as the bootstrap superuser (POSTGRES_USER). That
# superuser lives only inside this container: the backend never receives its credentials.
#   owner role   : runs Flyway, owns the database and every table, is not a superuser
#   runtime role : what the application connects as; the migrations grant it only what it needs
#
# It does NOT run again on a data directory that already exists. To change a role or a password later, use ALTER ROLE
# in psql, or `docker compose down -v` (which destroys the development data).
set -eu

# Names go into SQL identifiers, so hold them to the same pattern the application enforces
# (RuntimeRolePlaceholderGuard): a plain lower-case identifier. PostgreSQL reserves the pg_ prefix for its own roles.
valid_name() {
    case "$1" in
        pg_*) return 1 ;;
    esac
    printf '%s' "$1" | grep -Eq '^[a-z_][a-z0-9_]{0,62}$'
}

for name in "$PEOPLEHUB_DB_OWNER_ROLE" "$PEOPLEHUB_DB_RUNTIME_ROLE"; do
    if ! valid_name "$name"; then
        echo "01-roles.sh: invalid role name (need [a-z_][a-z0-9_]{0,62}, not starting with pg_)" >&2
        exit 1
    fi
done
if [ "$PEOPLEHUB_DB_OWNER_ROLE" = "$PEOPLEHUB_DB_RUNTIME_ROLE" ]; then
    echo "01-roles.sh: the owner and runtime roles must be different roles" >&2
    exit 1
fi

# Names and passwords are passed as psql variables and quoted by psql itself (:"ident" and :'literal'), never
# spliced into the SQL text.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v owner_role="$PEOPLEHUB_DB_OWNER_ROLE" -v owner_pw="$PEOPLEHUB_DB_OWNER_PASSWORD" \
    -v app_role="$PEOPLEHUB_DB_RUNTIME_ROLE" -v app_pw="$PEOPLEHUB_DB_RUNTIME_PASSWORD" \
    -v db="$POSTGRES_DB" <<'SQL'
CREATE ROLE :"owner_role" LOGIN PASSWORD :'owner_pw';
CREATE ROLE :"app_role"   LOGIN PASSWORD :'app_pw';

-- The owner owns the database, so it can create objects and the trusted btree_gist extension (V1) without being a
-- superuser. Nobody but the two application roles (and the bootstrap superuser) may even connect.
ALTER DATABASE :"db" OWNER TO :"owner_role";
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"db" TO :"owner_role", :"app_role";

-- The runtime role sees the schema; what it may do to each table is granted by the migrations, table by table.
GRANT USAGE ON SCHEMA public TO :"app_role";
SQL
