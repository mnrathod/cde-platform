package com.cde.platform.conversion;

import com.cde.platform.model.ConversionJob;
import com.cde.platform.model.ConversionJob.TargetFormat;
import com.cde.platform.model.Tenant;
import com.cde.platform.model.User;
import com.cde.platform.repository.ConversionJobRepository;
import com.cde.platform.repository.TenantRepository;
import com.cde.platform.repository.UserRepository;
import com.cde.platform.tenancy.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The required cross-tenant gate for conversion jobs (§5.6).
 *
 * <p>A conversion job is a new resource type, so it needs its own entry in
 * this suite rather than inheriting anyone's confidence. What is asserted is
 * the mechanism, not the convention: the repository methods carry no tenant
 * predicate at all — deliberately, because Row-Level Security is meant to
 * supply it — so if RLS were not doing its job these would return the other
 * tenant's rows.
 */
@SpringBootTest
class ConversionJobIsolationTest {

    @Autowired ConversionJobRepository jobs;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbcTemplate;

    private long acmeId;
    private long globexId;
    private UUID acmeJobPublicId;
    private UUID globexJobPublicId;

    @BeforeEach
    void createOneJobInEachOfTwoTenants() {
        acmeId = tenantOf("acme-" + System.nanoTime());
        globexId = tenantOf("globex-" + System.nanoTime());

        acmeJobPublicId = seedJob(acmeId, "acme-storage.example.test");
        globexJobPublicId = seedJob(globexId, "globex-storage.example.test");
    }

    private long tenantOf(String slug) {
        return tenants.save(
            Tenant.builder().slug(slug).name("Test tenant " + slug).build()).getId();
    }

    private UUID seedJob(long tenantId, String sourceHost) {
        return TenantContext.callAsTenant(tenantId, () -> {
            User submitter = users.save(User.builder()
                .username("submitter-" + tenantId)
                .email("submitter-" + tenantId + "@example.test")
                .password("{noop}irrelevant")
                .role(User.Role.ADMIN)
                .build());
            UUID publicId = UUID.randomUUID();
            jobs.save(ConversionJob.submitted(
                publicId, submitter.getId(), sourceHost, TargetFormat.PDF));
            return publicId;
        });
    }

    @Test
    @DisplayName("one tenant cannot fetch another tenant's job by its public id")
    void lookupByPublicIdIsScoped() {
        // The identifier is a UUID, so this is not a guess an attacker would
        // make — but a job id travels in a Location header and in integrator
        // logs, and "hard to guess" is not an access control.
        var ownJob = TenantContext.callAsTenant(acmeId,
            () -> jobs.findByPublicId(acmeJobPublicId));
        var othersJob = TenantContext.callAsTenant(acmeId,
            () -> jobs.findByPublicId(globexJobPublicId));

        assertThat(ownJob).isPresent();
        assertThat(othersJob)
            .as("Acme must not see Globex's job, and must not be able to tell "
                + "it apart from one that does not exist")
            .isEmpty();
    }

    @Test
    @DisplayName("a listing returns only the caller's own jobs")
    void listingIsScoped() {
        List<String> acmeHosts = TenantContext.callAsTenant(acmeId, () ->
            jobs.findAll().stream().map(ConversionJob::getSourceHost).toList());

        assertThat(acmeHosts)
            .contains("acme-storage.example.test")
            .doesNotContain("globex-storage.example.test");
    }

    @Test
    @DisplayName("raw SQL with no WHERE clause is still scoped")
    void rawSqlWithoutAPredicateIsStillScoped() {
        // The property RLS is supposed to provide, and the only one that holds
        // against a repository method nobody has written yet.
        List<String> asAcme = TenantContext.callAsTenant(acmeId, () ->
            jdbcTemplate.queryForList(
                "SELECT source_host FROM conversion_jobs", String.class));
        List<String> asGlobex = TenantContext.callAsTenant(globexId, () ->
            jdbcTemplate.queryForList(
                "SELECT source_host FROM conversion_jobs", String.class));

        assertThat(asAcme)
            .contains("acme-storage.example.test")
            .doesNotContain("globex-storage.example.test");
        assertThat(asGlobex)
            .contains("globex-storage.example.test")
            .doesNotContain("acme-storage.example.test");
    }

    @Test
    @DisplayName("the recovery query for unfinished jobs is scoped too")
    void unfinishedJobLookupIsScoped() {
        // Startup recovery reads across the table. It runs per tenant for this
        // reason: a query written to sweep everything would sweep everything.
        List<String> asAcme = TenantContext.callAsTenant(acmeId, () ->
            jobs.findByStatusIn(List.of(
                    ConversionJob.Status.PENDING, ConversionJob.Status.RUNNING))
                .stream().map(ConversionJob::getSourceHost).toList());

        assertThat(asAcme)
            .contains("acme-storage.example.test")
            .doesNotContain("globex-storage.example.test");
    }

    @Test
    @DisplayName("the table has no column that could hold the source link")
    void noColumnCanHoldTheCredential() {
        // The source URL is a presigned bearer credential. Asserted against
        // the schema rather than the entity, because a future migration adding
        // the column is exactly how this protection would be lost.
        List<String> columns = jdbcTemplate.queryForList(
            "SELECT column_name FROM information_schema.columns "
            + "WHERE table_name = 'conversion_jobs'", String.class);

        assertThat(columns)
            .isNotEmpty()
            .as("no column may hold the presigned URL; only its host is kept")
            .noneMatch(column -> column.contains("url"))
            .contains("source_host");
    }
}
