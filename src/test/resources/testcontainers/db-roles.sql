-- Database roles for tests, provisioned the way infrastructure provisions them in production (README, "Database roles"):
-- Flyway never creates roles. Applied by Testcontainers right after the container starts, before the application
-- connects. Passwords are throwaway test values.
--
-- The container's own user (`test`, a superuser) is what existing tests use for both Flyway and the application. The two
-- roles below exist for the tests that prove the privilege boundary: peoplehub_app is the least-privileged runtime role,
-- peoplehub_owner is a non-superuser migration/owner role.
-- (No DO blocks in this file: Testcontainers splits the script on semicolons.)
CREATE ROLE peoplehub_owner LOGIN PASSWORD 'peoplehub_owner';
CREATE ROLE peoplehub_app LOGIN PASSWORD 'peoplehub_app';

-- The owner creates the schema objects (and btree_gist, a trusted extension, which needs CREATE on the database).
-- `test` is PostgreSQLContainer's default database name.
GRANT CREATE ON DATABASE test TO peoplehub_owner;
GRANT CREATE, USAGE ON SCHEMA public TO peoplehub_owner;

-- The runtime role connects and sees the schema; what it may do to each table is granted by the migrations.
GRANT USAGE ON SCHEMA public TO peoplehub_app;
