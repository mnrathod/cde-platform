package com.cde.platform.cde.api;

import com.cde.platform.model.Project;
import com.cde.platform.model.User;
import com.cde.platform.repository.ProjectRepository;
import com.cde.platform.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The CDE lifecycle as a client actually reaches it.
 *
 * <p>The service-level tests prove the state machine and the database refuse
 * what they should. This proves the same rules survive the trip through HTTP —
 * that an illegal move comes back as a conflict a client can act on rather than
 * as a five-hundred, that the lineage a caller reads back is the one that was
 * written, and that a published revision is as frozen through the API as it is
 * in the table.
 *
 * <p>Not transactional, deliberately. The lifecycle spans several requests and
 * the assertions are about what a later request sees, so the writes have to
 * actually commit — a rolled-back test would assert against state no client
 * could ever observe.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = CdeApiIntegrationTest.USERNAME,
              authorities = { "container:read", "container:write", "container:share",
                              "container:publish", "container:reject", "container:archive" })
class CdeApiIntegrationTest {

    static final String USERNAME = "cde-api-user";

    @Autowired MockMvc           mockMvc;
    @Autowired ProjectRepository projectRepository;
    @Autowired UserRepository    userRepository;

    private Long projectId;

    @BeforeEach
    void createAProject() {
        User owner = userRepository.findByUsername(USERNAME).orElseGet(() ->
            userRepository.save(User.builder()
                .username(USERNAME)
                .email(USERNAME + "@example.test")
                .password("{noop}irrelevant")
                .role(User.Role.ENGINEER)
                .build()));

        projectId = projectRepository.save(Project.builder()
            .name("CDE API " + System.nanoTime())
            .owner(owner)
            .build()).getId();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private long createContainer(String reference) throws Exception {
        MvcResult result = mockMvc.perform(
                post("/api/cde/projects/{id}/containers", projectId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"containerReference":"%s",
                         "namingFields":{"originator":"XYZ","type":"DR"}}""".formatted(reference)))
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andReturn();
        return idFrom(result);
    }

    private long createRevision(long containerId, String code) throws Exception {
        MvcResult result = mockMvc.perform(
                post("/api/cde/containers/{id}/revisions", containerId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"revisionCode":"%s"}""".formatted(code)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.state").value("WORK_IN_PROGRESS"))
            .andReturn();
        return idFrom(result);
    }

    private void transition(long revisionId, String toState, String reason) throws Exception {
        mockMvc.perform(post("/api/cde/revisions/{id}/transitions", revisionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"toState":"%s","reason":"%s"}""".formatted(toState, reason)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.revision.state").value(toState))
            .andExpect(jsonPath("$.transition.toState").value(toState));
    }

    private long idFrom(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        // Deliberately crude rather than binding a DTO: reading the field back
        // out of the raw body is what a client without a generated model does,
        // and it fails loudly if the field is renamed.
        int start = body.indexOf("\"id\":") + 5;
        int end = body.indexOf(',', start);
        return Long.parseLong(body.substring(start, end).trim());
    }

    // ── The lifecycle ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("the lifecycle over HTTP")
    class Lifecycle {

        @Test
        @DisplayName("a revision moves work in progress -> shared -> published, and records why")
        void fullLifecycleIsDrivenAndAudited() throws Exception {
            long container = createContainer("PRJ-XYZ-ZZ-00-DR-A-" + System.nanoTime());
            long revision = createRevision(container, "P01.01");

            transition(revision, "SHARED", "Issued for coordination.");
            transition(revision, "PUBLISHED", "Approved for construction.");

            mockMvc.perform(get("/api/cde/revisions/{id}", revision))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentPublished").value(true))
                .andExpect(jsonPath("$.publishedBy").value(USERNAME))
                .andExpect(jsonPath("$.approvalReason").value("Approved for construction."));

            // Creation, sharing and publication — three entries, in order, each
            // naming its actor. This is the record that has to answer "on whose
            // authority" years later.
            mockMvc.perform(get("/api/cde/revisions/{id}/transitions", revision))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[1].toState").value("SHARED"))
                .andExpect(jsonPath("$[2].toState").value("PUBLISHED"))
                .andExpect(jsonPath("$[2].performedBy").value(USERNAME))
                .andExpect(jsonPath("$[2].reason").value("Approved for construction."));
        }

        @Test
        @DisplayName("superseding archives the old revision and links the lineage both ways")
        void supersessionKeepsTheLineage() throws Exception {
            long container = createContainer("PRJ-XYZ-ZZ-01-DR-A-" + System.nanoTime());
            long first = createRevision(container, "P01.01");
            transition(first, "SHARED", "Issued for coordination.");
            transition(first, "PUBLISHED", "Approved for construction.");

            MvcResult superseding = mockMvc.perform(
                    post("/api/cde/containers/{id}/revisions", container)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"revisionCode":"C01","supersedesRevisionId":%d}""".formatted(first)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("WORK_IN_PROGRESS"))
                .andExpect(jsonPath("$.supersedesRevisionCode").value("P01.01"))
                .andReturn();

            long second = idFrom(superseding);

            // The published revision is not edited and not removed. It is
            // archived, still readable, and now points at what replaced it.
            mockMvc.perform(get("/api/cde/revisions/{id}", first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ARCHIVED"))
                .andExpect(jsonPath("$.supersededByRevisionCode").value("C01"))
                .andExpect(jsonPath("$.currentPublished").value(false));

            mockMvc.perform(get("/api/cde/containers/{id}/revisions", container))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].id").value(second));
        }
    }

    // ── Refusals ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("refusals reach the client as something it can act on")
    class Refusals {

        @Test
        @DisplayName("an illegal move is a conflict naming both states, not a server error")
        void illegalTransitionIsAConflict() throws Exception {
            long container = createContainer("PRJ-XYZ-ZZ-02-DR-A-" + System.nanoTime());
            long revision = createRevision(container, "P01.01");

            // Work in progress cannot be published: it has to be shared and
            // reviewed first, which is the whole point of the intermediate
            // state.
            mockMvc.perform(post("/api/cde/revisions/{id}/transitions", revision)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"toState":"PUBLISHED","reason":"Skipping review."}"""))
                .andExpect(status().isConflict())
                .andExpect(header().string("Content-Type",
                                           containsString("application/problem+json")))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail")
                    .value(containsString("cannot move directly to published")))
                .andExpect(jsonPath("$.traceId").exists());
        }

        @Test
        @DisplayName("a transition with no reason is refused, naming the field")
        void everyTransitionMustSayWhy() throws Exception {
            long container = createContainer("PRJ-XYZ-ZZ-03-DR-A-" + System.nanoTime());
            long revision = createRevision(container, "P01.01");

            mockMvc.perform(post("/api/cde/revisions/{id}/transitions", revision)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"toState":"SHARED","reason":"  "}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.invalidFields[0].field").value("reason"));
        }

        @Test
        @DisplayName("a duplicate container reference is refused rather than silently accepted")
        void containerReferencesAreUnique() throws Exception {
            String reference = "PRJ-XYZ-ZZ-04-DR-A-" + System.nanoTime();
            createContainer(reference);

            mockMvc.perform(post("/api/cde/projects/{id}/containers", projectId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"containerReference":"%s"}""".formatted(reference)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString(reference)));
        }

        @Test
        @DisplayName("a container under a project that is not there reports not found")
        void unknownProjectIsNotFound() throws Exception {
            mockMvc.perform(get("/api/cde/projects/{id}/containers", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No such project."));
        }

        @Test
        @DisplayName("a published revision's suitability code cannot be changed")
        void publishedRevisionsAreFrozen() throws Exception {
            long container = createContainer("PRJ-XYZ-ZZ-05-DR-A-" + System.nanoTime());
            long revision = createRevision(container, "P01.01");
            transition(revision, "SHARED", "Issued for coordination.");
            transition(revision, "PUBLISHED", "Approved for construction.");

            mockMvc.perform(put("/api/cde/revisions/{id}/suitability-code", revision)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"suitabilityCodeId":null}"""))
                .andExpect(status().isConflict());
        }
    }

    // ── Suitability codes ────────────────────────────────────────────────────

    @Nested
    @DisplayName("suitability codes")
    class SuitabilityCodes {

        @Test
        @DisplayName("a project starts with none, and gets the ones it defines for itself")
        void codesAreProjectPopulated() throws Exception {
            // Nothing is seeded. The code tables printed in the standard are
            // copyrighted, so the product ships the mechanism and no values.
            mockMvc.perform(get("/api/cde/projects/{id}/suitability-codes", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

            mockMvc.perform(post("/api/cde/projects/{id}/suitability-codes", projectId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"code":"S2","description":"Suitable for information",
                         "displayOrder":20,"validInState":"SHARED"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("S2"))
                .andExpect(jsonPath("$.active").value(true));

            mockMvc.perform(get("/api/cde/projects/{id}/suitability-codes", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        }

        @Test
        @DisplayName("a code restricted to one state is refused in another")
        void aCodeCannotBeAppliedOutsideItsState() throws Exception {
            MvcResult created = mockMvc.perform(
                    post("/api/cde/projects/{id}/suitability-codes", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"code":"A1","description":"Approved for construction",
                             "validInState":"PUBLISHED"}"""))
                .andExpect(status().isCreated())
                .andReturn();
            long code = idFrom(created);

            long container = createContainer("PRJ-XYZ-ZZ-06-DR-A-" + System.nanoTime());
            long revision = createRevision(container, "P01.01");

            // Work in progress is unverified. Labelling it approved for
            // construction is precisely the confusion the code list exists to
            // prevent, so it is refused rather than recorded.
            mockMvc.perform(put("/api/cde/revisions/{id}/suitability-code", revision)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"suitabilityCodeId":%d}""".formatted(code)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("A1")));
        }
    }
}
