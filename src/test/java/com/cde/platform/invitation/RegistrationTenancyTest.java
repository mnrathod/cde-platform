package com.cde.platform.invitation;

import com.cde.platform.model.Project;
import com.cde.platform.model.Tenant;
import com.cde.platform.model.User;
import com.cde.platform.repository.ProjectRepository;
import com.cde.platform.repository.TenantRepository;
import com.cde.platform.repository.UserRepository;
import com.cde.platform.tenancy.TenancyProperties;
import com.cde.platform.tenancy.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Where a self-service registration lands.
 *
 * <p>It landed in the deployment's default tenant, which meant anyone who could
 * reach {@code /api/auth/register} could read every project in the deployment.
 * Row-Level Security was enforcing correctly the entire time and the isolation
 * suite was green throughout — both true, and neither helped, because
 * registration had already put everybody on the same side of the boundary.
 * There was nothing left to isolate.
 *
 * <p>So this asserts the boundary by driving it rather than by reading
 * {@code tenant_id} columns: two accounts, and whether either can reach the
 * other's work. A test that read the ids would pass on a deployment where the
 * ids differ and the policy leaks anyway.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RegistrationTenancyTest {

    private static final String PASSWORD = "correct-horse-battery-staple-42";

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository users;
    @Autowired ProjectRepository projects;
    @Autowired TenantRepository tenants;
    @Autowired TenancyProperties tenancyProperties;

    private static String unique(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    /** Registers and returns the whole reply, which carries a usable token. */
    private String register(String username) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"%s","email":"%s@example.test","password":"%s"}
                    """.formatted(username, username, PASSWORD)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
    }

    private String tokenFrom(String authResponse) {
        int start = authResponse.indexOf("\"token\":\"") + 9;
        return authResponse.substring(start, authResponse.indexOf('"', start));
    }

    private long createProject(String token, String name) throws Exception {
        String created = mockMvc.perform(post("/api/projects")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"%s","phase":"DESIGN"}
                    """.formatted(name)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        int start = created.indexOf("\"id\":") + 5;
        return Long.parseLong(created.substring(start, created.indexOf(',', start)).trim());
    }

    /** The tenant a registration used to land in, before it got one of its own. */
    private Tenant defaultTenant() {
        return tenants.findBySlug(tenancyProperties.getDefaultTenantSlug()).orElseThrow(
            () -> new IllegalStateException(
                "No tenant with slug '" + tenancyProperties.getDefaultTenantSlug()
                + "'. The deployment always has one; if this fires, tenancy setup changed."));
    }

    // ── The fix ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("two registrations cannot see each other's projects")
    void separateRegistrationsAreSeparateOrganisations() throws Exception {
        String firstToken = tokenFrom(register(unique("solo-a")));
        String secondToken = tokenFrom(register(unique("solo-b")));

        long firstProject = createProject(firstToken, "First party's work");

        mockMvc.perform(get("/api/projects").header("Authorization", "Bearer " + secondToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));

        // The §5.6 rule: never a 200 for another organisation's object id.
        mockMvc.perform(get("/api/documents/project/" + firstProject)
                .header("Authorization", "Bearer " + secondToken))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a new registration cannot see what was already in the deployment")
    void aNewAccountSeesNothingThatExistedBefore() throws Exception {
        // Put the pre-existing work there rather than hoping to find it. This
        // used to take whatever `users.findAll()` returned first and assume it
        // belonged to the default tenant, which made the test depend on the
        // demonstration seeder having run — and the seeder only runs on an
        // empty database, so whether this test had a precondition at all came
        // down to which Spring context started first. It passed in a full run
        // and failed on its own, which is the wrong way round: a guard nobody
        // can run in isolation is a guard nobody trusts.
        long defaultTenantId = defaultTenant().getId();
        TenantContext.runAsTenant(defaultTenantId, () -> {
            User owner = users.save(User.builder()
                .username(unique("incumbent"))
                .email(unique("incumbent") + "@example.test")
                .password("not-used-in-this-test")
                .role(User.Role.ADMIN).build());
            projects.save(Project.builder()
                .name("Pre-existing work " + System.nanoTime()).owner(owner).build());
        });

        long projectsInDefaultTenant =
            TenantContext.callAsTenant(defaultTenantId, projects::count);
        assertThat(projectsInDefaultTenant)
            .as("the default tenant needs a project for this test to mean anything")
            .isGreaterThan(0);

        String token = tokenFrom(register(unique("outsider")));

        mockMvc.perform(get("/api/projects").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("the registrant administers the organisation it just created")
    void theFounderCanInviteOthers() throws Exception {
        // Not an escalation: the tenant was created empty by this same request,
        // so the authority covers nothing but what the caller is about to put
        // there. Without it a one-member organisation could never gain a
        // second member.
        String response = register(unique("founder"));
        assertThat(response).contains("\"role\":\"ADMIN\"");

        mockMvc.perform(post("/api/invitations")
                .header("Authorization", "Bearer " + tokenFrom(response))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"colleague@example.test","role":"ENGINEER"}
                    """))
            .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("a username taken in another organisation is still refused")
    void identityIsUniqueAcrossTheDeployment() throws Exception {
        // username and email are globally unique, because login resolves the
        // tenant from the username alone. The in-tenant existence check that
        // used to guard this saw only the caller's own tenant — harmless while
        // everyone shared one, and a 500 on the constraint the moment they
        // stopped.
        String taken = unique("duplicate");
        register(taken);

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"%s","email":"other-%s@example.test","password":"%s"}
                    """.formatted(taken, taken, PASSWORD)))
            .andExpect(status().isConflict());
    }
}
