package com.cde.platform.tenancy;

import com.cde.platform.model.Project;
import com.cde.platform.model.Tenant;
import com.cde.platform.model.User;
import com.cde.platform.repository.ProjectRepository;
import com.cde.platform.repository.TenantRepository;
import com.cde.platform.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The required cross-tenant gate. A leak here is a company-ending event, so
 * these assert the mechanism rather than the convention.
 *
 * <p>The distinction matters. A test that calls a repository method which
 * happens to filter by tenant proves only that the method was written
 * correctly; it would keep passing after someone added a second method that
 * forgot. What is asserted here is that isolation survives a query with
 * <em>no tenant predicate at all</em> — raw SQL with no {@code WHERE} clause —
 * because that is the property Row-Level Security is supposed to provide and
 * the only one that holds against code nobody has written yet.
 *
 * <p>These tests found a real hole when first written: with every policy
 * created and both RLS flags set, a superuser connection saw every tenant's
 * rows. PostgreSQL exempts superusers and BYPASSRLS roles silently, and FORCE
 * binds only the table owner. Everything below therefore also serves as a
 * regression test on the connection running as the restricted role.
 */
@SpringBootTest
class TenantIsolationIntegrationTest {

    @Autowired TenantRepository  tenantRepo;
    @Autowired ProjectRepository projectRepo;
    @Autowired UserRepository    userRepo;
    @Autowired JdbcTemplate      jdbcTemplate;

    private long acmeId;
    private long globexId;

    @BeforeEach
    void createTwoTenantsWithOneProjectEach() {
        acmeId = tenantOf("acme-" + System.nanoTime(), "Acme Construction");
        globexId = tenantOf("globex-" + System.nanoTime(), "Globex Engineering");

        seedProject(acmeId, "Acme Tower");
        seedProject(globexId, "Globex Refinery");
    }

    private long tenantOf(String slug, String name) {
        return tenantRepo.save(Tenant.builder().slug(slug).name(name).build()).getId();
    }

    private void seedProject(long tenantId, String projectName) {
        TenantContext.runAsTenant(tenantId, () -> {
            User owner = userRepo.save(User.builder()
                .username("owner-" + tenantId)
                .email("owner-" + tenantId + "@example.test")
                .password("{noop}irrelevant")
                .role(User.Role.ADMIN)
                .build());
            projectRepo.save(Project.builder().name(projectName).owner(owner).build());
        });
    }

    @Nested
    @DisplayName("reads")
    class Reads {

        @Test
        @DisplayName("a query with no tenant predicate still returns only one tenant's rows")
        void rawSqlWithoutAWhereClauseIsStillScoped() {
            List<String> asAcme = TenantContext.callAsTenant(acmeId, () ->
                jdbcTemplate.queryForList("SELECT name FROM projects", String.class));

            List<String> asGlobex = TenantContext.callAsTenant(globexId, () ->
                jdbcTemplate.queryForList("SELECT name FROM projects", String.class));

            assertThat(asAcme).contains("Acme Tower").doesNotContain("Globex Refinery");
            assertThat(asGlobex).contains("Globex Refinery").doesNotContain("Acme Tower");
        }

        @Test
        @DisplayName("findAll cannot see across the boundary either")
        void repositoryReadsAreScoped() {
            List<String> asAcme = TenantContext.callAsTenant(acmeId, () ->
                projectRepo.findAll().stream().map(Project::getName).toList());

            assertThat(asAcme).contains("Acme Tower").doesNotContain("Globex Refinery");
        }

        @Test
        @DisplayName("fetching another tenant's row by its primary key finds nothing")
        void findByIdAcrossTenantsReturnsEmpty() {
            Long globexProjectId = TenantContext.callAsTenant(globexId, () ->
                projectRepo.findAll().stream()
                    .filter(p -> p.getName().equals("Globex Refinery"))
                    .findFirst().orElseThrow().getId());

            // Knowing the id is not enough. This is the IDOR case: an
            // authenticated caller guessing or enumerating another tenant's
            // identifiers must get nothing back, not a row.
            var found = TenantContext.callAsTenant(acmeId, () -> projectRepo.findById(globexProjectId));

            assertThat(found).isEmpty();
        }

        @Test
        @DisplayName("counts do not leak the existence of other tenants' rows")
        void aggregatesAreScoped() {
            long asAcme = TenantContext.callAsTenant(acmeId, () -> projectRepo.count());
            long asGlobex = TenantContext.callAsTenant(globexId, () -> projectRepo.count());

            // A count that included the other tenant would disclose volume even
            // without disclosing content.
            assertThat(asAcme).isEqualTo(1);
            assertThat(asGlobex).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("writes")
    class Writes {

        private static final String INSERT_PROJECT =
            "INSERT INTO projects (name, tenant_id, owner_id) "
            + "VALUES (?, ?, (SELECT id FROM users LIMIT 1))";

        @Test
        @DisplayName("the same insert succeeds into the caller's own tenant")
        void insertIntoOwnTenantSucceeds() {
            // The control for the test below. Without it, an INSERT that was
            // simply malformed — a missing column, a bad cast — would throw
            // just the same and the rejection test would pass having proved
            // nothing about tenancy.
            int rowsInserted = TenantContext.callAsTenant(acmeId, () ->
                jdbcTemplate.update(INSERT_PROJECT, "Acme Annexe", acmeId));

            assertThat(rowsInserted).isEqualTo(1);
        }

        @Test
        @DisplayName("the identical insert into another tenant is refused by the policy")
        void insertIntoAnotherTenantIsRejected() {
            assertThatThrownBy(() ->
                TenantContext.runAsTenant(acmeId, () ->
                    jdbcTemplate.update(INSERT_PROJECT, "Smuggled", globexId)))
                .isInstanceOf(DataAccessException.class)
                // Spring translates PostgreSQL's insufficient_privilege (42501)
                // to BadSqlGrammarException, so the reason is on the cause
                // rather than the top-level message.
                .hasStackTraceContaining("row-level security");

            // And nothing landed.
            List<String> globexProjects = TenantContext.callAsTenant(globexId, () ->
                jdbcTemplate.queryForList("SELECT name FROM projects", String.class));
            assertThat(globexProjects).doesNotContain("Smuggled");
        }

        @Test
        @DisplayName("another tenant's row cannot be updated")
        void updateAcrossTenantsAffectsNothing() {
            int rowsChanged = TenantContext.callAsTenant(acmeId, () ->
                jdbcTemplate.update("UPDATE projects SET name = ? WHERE tenant_id = ?",
                                    "Renamed by Acme", globexId));

            assertThat(rowsChanged).isZero();

            List<String> globexProjects = TenantContext.callAsTenant(globexId, () ->
                jdbcTemplate.queryForList("SELECT name FROM projects", String.class));
            assertThat(globexProjects).containsExactly("Globex Refinery");
        }

        @Test
        @DisplayName("another tenant's row cannot be deleted")
        void deleteAcrossTenantsAffectsNothing() {
            int rowsDeleted = TenantContext.callAsTenant(acmeId, () ->
                jdbcTemplate.update("DELETE FROM projects WHERE tenant_id = ?", globexId));

            assertThat(rowsDeleted).isZero();

            long stillThere = TenantContext.callAsTenant(globexId, () -> projectRepo.count());
            assertThat(stillThere).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("absence of context")
    class FailClosed {

        @Test
        @DisplayName("no tenant bound reads nothing, rather than everything")
        void unboundContextSeesNoRows() {
            TenantContextBinder.clear();

            List<String> visible =
                jdbcTemplate.queryForList("SELECT name FROM projects", String.class);

            // The direction of this failure is the whole point. Code that
            // reaches the database without establishing context — a scheduled
            // job, a consumer, a bug — must under-read, never over-read.
            assertThat(visible).isEmpty();
        }
    }

    @Nested
    @DisplayName("connection reuse")
    class Pooling {

        @Test
        @DisplayName("a connection returned to the pool carries no tenant to its next borrower")
        void tenantDoesNotSurviveConnectionReturn() {
            // Borrow, scope to Acme, release.
            TenantContext.runAsTenant(acmeId, () ->
                jdbcTemplate.queryForList("SELECT name FROM projects", String.class));

            TenantContextBinder.clear();

            // The next borrower very likely gets the same physical connection.
            // If SET ROLE or app.tenant_id survived, this would return Acme's
            // rows to a caller with no claim to them.
            List<String> leaked =
                jdbcTemplate.queryForList("SELECT name FROM projects", String.class);

            assertThat(leaked).isEmpty();
        }
    }
}
