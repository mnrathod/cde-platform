package com.cde.platform.cde.api;

import com.cde.platform.cde.model.InformationContainer;
import com.cde.platform.cde.repository.InformationContainerRepository;
import com.cde.platform.cde.service.ContainerLifecycleService;
import com.cde.platform.model.Project;
import com.cde.platform.model.Tenant;
import com.cde.platform.model.User;
import com.cde.platform.repository.ProjectRepository;
import com.cde.platform.repository.TenantRepository;
import com.cde.platform.repository.UserRepository;
import com.cde.platform.tenancy.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Another tenant's containers are not reachable through any of the new
 * endpoints, by id or otherwise.
 *
 * <p>Required for every new resource type, and it is not a formality here: the
 * CDE holds the contractual record for a project, and information about a
 * secure site is treated as classified material. A cross-tenant read of a
 * container is the worst thing this system can do.
 *
 * <p>The caller below holds every container permission, so nothing it is
 * refused is refused for lack of one. What stops it is Row-Level Security, and
 * a `404` rather than a `403` is the deliberate answer — telling the caller the
 * id exists but is not theirs is itself a disclosure.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = CdeCrossTenantIsolationTest.USERNAME,
              authorities = { "container:read", "container:write", "container:share",
                              "container:publish", "container:reject", "container:archive" })
class CdeCrossTenantIsolationTest {

    static final String USERNAME = "cde-tenant-a-user";

    @Autowired MockMvc                        mockMvc;
    @Autowired TenantRepository               tenantRepository;
    @Autowired ProjectRepository              projectRepository;
    @Autowired UserRepository                 userRepository;
    @Autowired InformationContainerRepository containerRepository;
    @Autowired ContainerLifecycleService      lifecycle;

    @Autowired com.cde.platform.cde.repository.ContainerRevisionRepository revisionRepository;

    /** Belonging to the tenant the caller is not in. */
    private long foreignTenantId;
    private long foreignProjectId;
    private long foreignContainerId;
    private long foreignRevisionId;

    /** Belonging to the caller's own tenant — the positive control. */
    private long ownProjectId;

    @BeforeEach
    void seedBothTenants() {
        // The caller's own tenant is whichever the test fixture binds; only the
        // other one has to be created.
        User caller = userRepository.findByUsername(USERNAME).orElseGet(() ->
            userRepository.save(User.builder()
                .username(USERNAME).email(USERNAME + "@example.test")
                .password("{noop}irrelevant").role(User.Role.ENGINEER).build()));

        ownProjectId = projectRepository.save(
            Project.builder().name("Own " + System.nanoTime()).owner(caller).build()).getId();

        long foreignTenant = tenantRepository.save(Tenant.builder()
            .slug("foreign-" + System.nanoTime())
            .name("Another Appointed Party")
            .build()).getId();
        foreignTenantId = foreignTenant;

        TenantContext.runAsTenant(foreignTenant, () -> {
            User theirs = userRepository.save(User.builder()
                .username("foreign-" + foreignTenant)
                .email("foreign-" + foreignTenant + "@example.test")
                .password("{noop}irrelevant").role(User.Role.ENGINEER).build());

            Project theirProject = projectRepository.save(
                Project.builder().name("Their Refinery").owner(theirs).build());
            foreignProjectId = theirProject.getId();

            InformationContainer theirContainer = containerRepository.save(
                InformationContainer.builder()
                    .project(theirProject)
                    .containerReference("THEIRS-ZZ-00-DR-A-0001")
                    .createdBy(theirs)
                    .build());
            foreignContainerId = theirContainer.getId();

            foreignRevisionId = lifecycle
                .startWorkInProgress(theirContainer, "P01.01", theirs).getId();
        });
    }

    @Test
    @DisplayName("the endpoints work at all for the caller's own tenant")
    void theCallerCanReachItsOwnProject() throws Exception {
        // Without this, every assertion below would also pass if the whole
        // controller were broken — which is the most comfortable way for an
        // isolation suite to be green.
        mockMvc.perform(get("/api/cde/projects/{id}/containers", ownProjectId))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the rows the caller is refused genuinely exist, in the other tenant")
    void theForeignFixtureIsReal() {
        // The other half of the same worry. Every refusal below is a 404, and a
        // 404 is exactly what a fixture that silently failed to insert would
        // also produce — so the suite would pass most convincingly at the
        // moment it stopped testing anything. Read as the tenant that owns
        // them, all three have to be there.
        assertThat(foreignContainerId).isPositive();

        TenantContext.runAsTenant(foreignTenantId, () -> {
            assertThat(projectRepository.findById(foreignProjectId)).isPresent();
            assertThat(containerRepository.findById(foreignContainerId)).isPresent();
            assertThat(revisionRepository.findById(foreignRevisionId)).isPresent();
        });
    }

    @Test
    @DisplayName("another tenant's containers cannot be listed")
    void listingAnotherTenantsProjectFindsNothing() throws Exception {
        mockMvc.perform(get("/api/cde/projects/{id}/containers", foreignProjectId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("No such project."));
    }

    @Test
    @DisplayName("another tenant's container cannot be read by its id")
    void readingAnotherTenantsContainerFindsNothing() throws Exception {
        mockMvc.perform(get("/api/cde/containers/{id}", foreignContainerId))
            .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/cde/containers/{id}/revisions", foreignContainerId))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("another tenant's revision and its history cannot be read")
    void readingAnotherTenantsRevisionFindsNothing() throws Exception {
        mockMvc.perform(get("/api/cde/revisions/{id}", foreignRevisionId))
            .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/cde/revisions/{id}/transitions", foreignRevisionId))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("another tenant's revision cannot be moved through the state machine")
    void transitioningAnotherTenantsRevisionFindsNothing() throws Exception {
        mockMvc.perform(post("/api/cde/revisions/{id}/transitions", foreignRevisionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"toState":"SHARED","reason":"Issuing somebody else's drawing."}"""))
            .andExpect(status().isNotFound());

        mockMvc.perform(put("/api/cde/revisions/{id}/suitability-code", foreignRevisionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"suitabilityCodeId":null}"""))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("nothing can be written into another tenant's project")
    void writingIntoAnotherTenantsProjectFindsNothing() throws Exception {
        mockMvc.perform(post("/api/cde/projects/{id}/containers", foreignProjectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"containerReference":"INTRUDER-ZZ-00-DR-A-0001"}"""))
            .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/cde/containers/{id}/revisions", foreignContainerId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"revisionCode":"P09.09"}"""))
            .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/cde/projects/{id}/suitability-codes", foreignProjectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"code":"S9","description":"Injected"}"""))
            .andExpect(status().isNotFound());
    }
}
