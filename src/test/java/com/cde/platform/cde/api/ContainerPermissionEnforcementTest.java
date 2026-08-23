package com.cde.platform.cde.api;

import com.cde.platform.cde.domain.ContainerPermission;
import com.cde.platform.cde.domain.ContainerState;
import com.cde.platform.model.Project;
import com.cde.platform.model.User;
import com.cde.platform.repository.ProjectRepository;
import com.cde.platform.repository.UserRepository;
import com.cde.platform.security.RolePermissions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * That the permission each endpoint documents is the permission it actually
 * demands.
 *
 * <p>The specification says every operation states the permission it requires.
 * Until this suite existed, those sentences were claims about a check that did
 * not run — the only real gate was "is this request authenticated at all",
 * which is precisely the shape of failure that reads as a working control right
 * up until an audit.
 *
 * <p>The roles here are not hard-coded: each request is made with whatever
 * {@link RolePermissions} actually grants, so widening a role's grant makes the
 * corresponding refusal below fail rather than quietly becoming untrue.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ContainerPermissionEnforcementTest {

    @Autowired MockMvc           mockMvc;
    @Autowired ProjectRepository projectRepository;
    @Autowired UserRepository    userRepository;

    private Long projectId;

    @BeforeEach
    void createAProject() {
        User owner = userRepository.findByUsername("cde-permission-user").orElseGet(() ->
            userRepository.save(User.builder()
                .username("cde-permission-user")
                .email("cde-permission@example.test")
                .password("{noop}irrelevant")
                .role(User.Role.ENGINEER)
                .build()));

        projectId = projectRepository.save(Project.builder()
            .name("Permissions " + System.nanoTime())
            .owner(owner)
            .build()).getId();
    }

    private long idFrom(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        int start = body.indexOf("\"id\":") + 5;
        return Long.parseLong(body.substring(start, body.indexOf(',', start)).trim());
    }

    /** A principal holding exactly what the role really grants — nothing more. */
    private RequestPostProcessor actingAs(User.Role role) {
        return user("cde-permission-user")
            .authorities(RolePermissions.grantedTo(role).stream()
                .map(SimpleGrantedAuthority::new)
                .toList());
    }

    // ── The matrix ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("what each role may do")
    class Matrix {

        @Test
        @DisplayName("an engineer originates and shares, but cannot authorise for use")
        void engineersCannotPublish() throws Exception {
            long container = idFrom(mockMvc.perform(
                    post("/api/cde/projects/{id}/containers", projectId)
                        .with(actingAs(User.Role.ENGINEER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"containerReference":"PRJ-ENG-%d"}""".formatted(System.nanoTime())))
                .andExpect(status().isCreated())
                .andReturn());

            long revision = idFrom(mockMvc.perform(
                    post("/api/cde/containers/{id}/revisions", container)
                        .with(actingAs(User.Role.ENGINEER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"revisionCode":"P01.01"}"""))
                .andExpect(status().isCreated())
                .andReturn());

            mockMvc.perform(post("/api/cde/revisions/{id}/transitions", revision)
                    .with(actingAs(User.Role.ENGINEER))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"toState":"SHARED","reason":"Issued for coordination."}"""))
                .andExpect(status().isOk());

            // The refusal has to come from the state actually requested, not
            // from the endpoint: an engineer holds container:share, so the
            // endpoint's own gate lets this through and only the check on the
            // publish operation itself stops it. Asserted against a revision
            // that really is ready to publish, so nothing else can be the
            // reason for the refusal.
            mockMvc.perform(post("/api/cde/revisions/{id}/transitions", revision)
                    .with(actingAs(User.Role.ENGINEER))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"toState":"PUBLISHED","reason":"Approving my own work."}"""))
                .andExpect(status().isForbidden());

            // And it is genuinely still shared — the refusal did not half-apply.
            mockMvc.perform(get("/api/cde/revisions/{id}", revision)
                    .with(actingAs(User.Role.VIEWER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SHARED"));
        }

        @Test
        @DisplayName("a reviewer can publish what an engineer shared")
        void reviewersCanPublish() throws Exception {
            // The mirror of the test above. Without it, "engineers cannot
            // publish" would also pass if nobody could publish at all.
            long container = idFrom(mockMvc.perform(
                    post("/api/cde/projects/{id}/containers", projectId)
                        .with(actingAs(User.Role.ENGINEER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"containerReference":"PRJ-REV-OK-%d"}"""
                            .formatted(System.nanoTime())))
                .andExpect(status().isCreated())
                .andReturn());

            long revision = idFrom(mockMvc.perform(
                    post("/api/cde/containers/{id}/revisions", container)
                        .with(actingAs(User.Role.ENGINEER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"revisionCode":"P01.01"}"""))
                .andExpect(status().isCreated())
                .andReturn());

            mockMvc.perform(post("/api/cde/revisions/{id}/transitions", revision)
                    .with(actingAs(User.Role.ENGINEER))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"toState":"SHARED","reason":"Issued for coordination."}"""))
                .andExpect(status().isOk());

            mockMvc.perform(post("/api/cde/revisions/{id}/transitions", revision)
                    .with(actingAs(User.Role.REVIEWER))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"toState":"PUBLISHED","reason":"Approved for construction."}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision.state").value("PUBLISHED"));
        }

        @Test
        @DisplayName("a reviewer authorises information but does not originate it")
        void reviewersCannotCreateContainers() throws Exception {
            mockMvc.perform(post("/api/cde/projects/{id}/containers", projectId)
                    .with(actingAs(User.Role.REVIEWER))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"containerReference":"PRJ-REV-%d"}""".formatted(System.nanoTime())))
                .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a viewer reads and nothing else")
        void viewersAreReadOnly() throws Exception {
            mockMvc.perform(get("/api/cde/projects/{id}/containers", projectId)
                    .with(actingAs(User.Role.VIEWER)))
                .andExpect(status().isOk());

            mockMvc.perform(post("/api/cde/projects/{id}/containers", projectId)
                    .with(actingAs(User.Role.VIEWER))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"containerReference":"PRJ-VWR-%d"}""".formatted(System.nanoTime())))
                .andExpect(status().isForbidden());

            mockMvc.perform(post("/api/cde/revisions/{id}/transitions", 1L)
                    .with(actingAs(User.Role.VIEWER))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"toState":"SHARED","reason":"Issuing for coordination."}"""))
                .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an unauthenticated caller gets nowhere near any of it")
        void anonymousIsRefused() throws Exception {
            mockMvc.perform(get("/api/cde/projects/{id}/containers", projectId))
                .andExpect(status().isUnauthorized());
        }
    }

    // ── The mapping itself ───────────────────────────────────────────────────

    @Nested
    @DisplayName("the role mapping")
    class Mapping {

        @Test
        @DisplayName("no role is accidentally granted everything")
        void onlyAdministratorsHoldEveryPermission() {
            assertThat(RolePermissions.grantedTo(User.Role.ADMIN))
                .containsExactlyInAnyOrderElementsOf(ContainerPermission.ALL);

            for (User.Role role : List.of(User.Role.ENGINEER, User.Role.REVIEWER, User.Role.VIEWER)) {
                assertThat(RolePermissions.grantedTo(role))
                    .as("%s should not hold every permission", role)
                    .isNotEqualTo(ContainerPermission.ALL);
            }
        }

        @Test
        @DisplayName("a user with no role is granted nothing")
        void anAbsentRoleGrantsNothing() {
            // Not the least-privileged role. Defaulting a failed role lookup to
            // "viewer" would read as cautious and would quietly hand read
            // access to an account whose role did not load.
            assertThat(RolePermissions.grantedTo(null)).isEmpty();
            assertThat(RolePermissions.authoritiesFor(null)).isEmpty();
        }

        @Test
        @DisplayName("the role authority survives alongside the permissions")
        void roleAuthorityIsStillGranted() {
            // The actuator rules are written as hasRole('ADMIN'). Dropping this
            // would silently change who can scrape metrics, and nothing in this
            // module would have noticed.
            assertThat(RolePermissions.authoritiesFor(User.Role.ADMIN))
                .extracting(Object::toString)
                .contains("ROLE_ADMIN");
        }

        @Test
        @DisplayName("every state's required permission is one that exists")
        void stateMachinePermissionsAreReal() {
            for (ContainerState state : ContainerState.values()) {
                assertThat(ContainerPermission.ALL)
                    .as("permission required to reach %s", state)
                    .contains(state.requiredPermission());
            }
        }
    }
}
