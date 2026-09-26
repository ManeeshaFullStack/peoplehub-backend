package com.peoplehub.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.TestDatabaseRoles;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The shared integration-test context connects the way a deployment does (b2-8 O7, O8): the
 * application as the least-privileged runtime role, which is neither a superuser nor exempt from
 * row-level security, the schema owned by the non-superuser owner role that Flyway ran as, and the
 * privileged fixture connection available only to code that asks for it by qualifier.
 */
@IntegrationTest
class IntegrationTestRolesTest {

    @Autowired private ApplicationContext context;
    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate applicationJdbc;
    @Autowired private JdbcClient applicationJdbcClient;
    @Autowired @PrivilegedFixture private JdbcTemplate fixtureJdbc;
    @Autowired @PrivilegedFixture private JdbcClient fixtureJdbcClient;
    @Autowired private Map<String, DataSource> allDataSources;
    @Autowired private List<JdbcTemplate> allJdbcTemplates;
    @Autowired private List<JdbcClient> allJdbcClients;

    @Test
    void theApplicationConnectsAsTheRuntimeRoleWithoutSuperuserOrRlsBypass() {
        for (JdbcTemplate jdbc :
                new JdbcTemplate[] {applicationJdbc, new JdbcTemplate(dataSource)}) {
            Map<String, Object> role =
                    jdbc.queryForMap(
                            "SELECT current_user AS name, r.rolsuper, r.rolbypassrls,"
                                    + " r.rolcreaterole, r.rolcreatedb"
                                    + " FROM pg_roles r WHERE r.rolname = current_user");
            assertThat(role)
                    .containsEntry("name", TestDatabaseRoles.RUNTIME_ROLE)
                    .containsEntry("rolsuper", false)
                    .containsEntry("rolbypassrls", false)
                    .containsEntry("rolcreaterole", false)
                    .containsEntry("rolcreatedb", false);
        }
        assertThat(applicationJdbcClient.sql("SELECT current_user").query(String.class).single())
                .isEqualTo(TestDatabaseRoles.RUNTIME_ROLE);
    }

    @Test
    void flywayMigratedAsTheOwnerRoleSoTheSchemaIsOwnedByIt() {
        assertThat(
                        fixtureJdbc.queryForList(
                                "SELECT DISTINCT installed_by FROM flyway_schema_history",
                                String.class))
                .containsExactly(TestDatabaseRoles.OWNER_ROLE);
        // Every table the application may use (tests also create scratch tables as the superuser).
        assertThat(
                        fixtureJdbc.queryForList(
                                "SELECT DISTINCT c.relowner::regrole::text FROM pg_class c"
                                        + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                                        + " WHERE n.nspname = 'public' AND c.relkind = 'r'"
                                        + " AND has_table_privilege(?, c.oid,"
                                        + " 'SELECT, INSERT, UPDATE, DELETE')",
                                String.class,
                                TestDatabaseRoles.RUNTIME_ROLE))
                .containsExactly(TestDatabaseRoles.OWNER_ROLE);
        Map<String, Object> owner =
                fixtureJdbc.queryForMap(
                        "SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = ?",
                        TestDatabaseRoles.OWNER_ROLE);
        assertThat(owner).containsEntry("rolsuper", false).containsEntry("rolbypassrls", false);
    }

    @Test
    void theFixtureConnectionIsPrivilegedAndSeparate() {
        assertThat(
                        fixtureJdbc.queryForObject(
                                "SELECT rolsuper FROM pg_roles WHERE rolname = current_user",
                                Boolean.class))
                .isTrue();
        assertThat(fixtureJdbcClient.sql("SELECT current_user").query(String.class).single())
                .isNotEqualTo(TestDatabaseRoles.RUNTIME_ROLE)
                .isNotEqualTo(TestDatabaseRoles.OWNER_ROLE);
        assertThat(fixtureJdbc).isNotSameAs(applicationJdbc);
        assertThat(fixtureJdbc.getDataSource()).isNotSameAs(dataSource);
    }

    @Test
    void theFixtureConnectionIsNeverAnApplicationCandidate() {
        // What an application bean receives when it asks by type, alone or as a collection: only
        // the application's own. The privileged helpers are not default candidates and the pool
        // behind them is not a DataSource bean at all.
        assertThat(context.getBeanNamesForType(DataSource.class)).hasSize(1);
        assertThat(allDataSources.values()).containsExactly(dataSource);
        assertThat(allJdbcTemplates).containsExactly(applicationJdbc);
        assertThat(allJdbcClients).containsExactly(applicationJdbcClient);
        assertThat(context.getBean(JdbcTemplate.class)).isSameAs(applicationJdbc);
        assertThat(context.getBean(JdbcClient.class)).isSameAs(applicationJdbcClient);
    }
}
