-- Database roles for tests, provisioned the way infrastructure provisions them in production (README, "Database roles"):
-- Flyway never creates roles. Applied by Testcontainers right after the container starts, before the application
-- connects. Passwords are throwaway test values.
--
-- The shared integration-test context runs Flyway as peoplehub_owner (a non-superuser migration/owner role) and the
-- application as peoplehub_app (the least-privileged runtime role). The container's own user (`test`, a superuser) is
-- used only by test fixtures, through the @PrivilegedFixture connection (b2-8).
-- (No DO blocks in this file: Testcontainers splits the script on semicolons.)
CREATE ROLE peoplehub_owner LOGIN PASSWORD 'peoplehub_owner';
CREATE ROLE peoplehub_app LOGIN PASSWORD 'peoplehub_app';

-- The owner creates the schema objects (and btree_gist, a trusted extension, which needs CREATE on the database).
-- `test` is PostgreSQLContainer's default database name.
GRANT CREATE ON DATABASE test TO peoplehub_owner;
GRANT CREATE, USAGE ON SCHEMA public TO peoplehub_owner;

-- The runtime role connects and sees the schema; what it may do to each table is granted by the migrations.
GRANT USAGE ON SCHEMA public TO peoplehub_app;
