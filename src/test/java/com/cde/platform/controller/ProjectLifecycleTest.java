package com.cde.platform.controller;

import com.cde.platform.model.Document;
import com.cde.platform.model.Project;
import com.cde.platform.model.User;
import com.cde.platform.repository.DocumentRepository;
import com.cde.platform.repository.ProjectRepository;
import com.cde.platform.repository.UserRepository;
import com.cde.platform.support.ActingAs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Creating, replacing and deleting a project.
 *
 * <p>The replace semantics are the part worth pinning down, because the
 * endpoint is deliberately inconsistent and the inconsistency is the design.
 * {@code PUT} replaces, so an omitted name, description or location is
 * cleared — that is what distinguishes it from a patch. Phase is the one
 * exception: a project moves through phases in order, and clearing one by
 * omission would silently regress a project to the start. A reader cannot tell
 * which fields behave which way from the method alone, so it is asserted here
 * rather than left to the description.
 *
 * <p>Deletion is tested for its answer on a second attempt as well as its
 * first. §3.4 wants {@code DELETE} idempotent, and "idempotent" here means a
 * repeat reports 404 rather than failing in some new way — a client retrying
 * after a timeout must not be left unable to tell whether its first attempt
 * worked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActingAs(value = User.Role.ENGINEER, username = ProjectLifecycleTest.USERNAME)
@DisplayName("a project")
class ProjectLifecycleTest {

    static final String USERNAME = "project-lifecycle-user";

    @Autowired MockMvc mockMvc;
    @Autowired ProjectRepository projectRepo;
    @Autowired DocumentRepository documentRepo;
    @Autowired UserRepository userRepo;

    private User owner;

    @BeforeEach
    void setUp() {
        owner = userRepo.findByUsername(USERNAME).orElseGet(() ->
            userRepo.save(User.builder()
                .username(USERNAME).email("project-lifecycle@example.test")
                .password("{noop}irrelevant").role(User.Role.ENGINEER).build()));
    }

    private org.springframework.test.web.servlet.ResultActions create(String json)
            throws Exception {
        return mockMvc.perform(post("/api/projects")
            .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private long idFrom(String body) {
        int start = body.indexOf("\"id\":") + 5;
        return Long.parseLong(body.substring(start, body.indexOf(',', start)).trim());
    }

    private Project stored(String name) {
        return projectRepo.save(Project.builder()
            .name(name + " " + System.nanoTime())
            .description("As built").location("Newcastle")
            .phase(Project.ProjectPhase.DESIGN).owner(owner).build());
    }

    @Nested
    @DisplayName("being created")
    class Creation {

        @Test
        @DisplayName("is created with the details it was given")
        void createsWithTheGivenDetails() throws Exception {
            create("""
                {"name":"Tyne Crossing","description":"Replacement deck",
                 "location":"Newcastle","phase":"CONSTRUCTION"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Tyne Crossing"))
                .andExpect(jsonPath("$.location").value("Newcastle"))
                .andExpect(jsonPath("$.phase").value("CONSTRUCTION"));
        }

        @Test
        @DisplayName("starts at the first phase when none is named")
        void defaultsToTheFirstPhase() throws Exception {
            // §1.2: every setting has a working default. A project with no
            // phase at all could not appear on a board organised by phase.
            create("""
                {"name":"Tyne Crossing"}""")
                .andExpect(jsonPath("$.phase").value("CONCEPT"));
        }

        @Test
        @DisplayName("is owned by whoever created it, not by whoever the body names")
        void ownerComesFromTheSession() throws Exception {
            // Ownership is not settable through this endpoint. Taking it from
            // the body would let a caller file a project under someone else and
            // inherit whatever that ownership grants.
            create("""
                {"name":"Tyne Crossing","owner":"someone-else"}""")
                .andExpect(jsonPath("$.ownerUsername").value(USERNAME));
        }

        @Test
        @DisplayName("starts with no documents counted")
        void startsEmpty() throws Exception {
            create("""
                {"name":"Tyne Crossing"}""")
                .andExpect(jsonPath("$.documentCount").value(0));
        }

        @Test
        @DisplayName("a project with no name is refused")
        void nameIsRequired() throws Exception {
            create("""
                {"description":"Replacement deck"}""")
                .andExpect(status().isUnprocessableContent());
        }

        @Test
        @DisplayName("a name of nothing but whitespace is refused")
        void blankNameIsRefused() throws Exception {
            create("""
                {"name":"   "}""").andExpect(status().isUnprocessableContent());
        }

        @Test
        @DisplayName("an oversized name is refused rather than truncated")
        void oversizedNameIsRefused() throws Exception {
            // Truncating would file the project under a name the creator did not
            // choose and cannot search for.
            create("""
                {"name":"%s"}""".formatted("x".repeat(201)))
                .andExpect(status().isUnprocessableContent());
        }

        @Test
        @DisplayName("a phase that is not a phase is refused, not defaulted")
        void unknownPhaseIsRefused() throws Exception {
            // Defaulting here would be worse than refusing: the caller asked for
            // a specific phase and would be told it succeeded.
            create("""
                {"name":"Tyne Crossing","phase":"DEMOLITION"}""")
                .andExpect(status().is4xxClientError());
        }
    }

    @Nested
    @DisplayName("being read")
    class Reading {

        @Test
        @DisplayName("appears in the list")
        void appearsInTheList() throws Exception {
            Project project = stored("Listed");

            String body = mockMvc.perform(get("/api/projects"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

            assertThat(body).contains(project.getName());
        }

        @Test
        @DisplayName("can be fetched on its own")
        void canBeFetchedAlone() throws Exception {
            Project project = stored("Fetched");

            mockMvc.perform(get("/api/projects/{id}", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(project.getName()));
        }

        @Test
        @DisplayName("one that does not exist is a 404")
        void unknownProjectIsNotFound() throws Exception {
            mockMvc.perform(get("/api/projects/9999999"))
                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("counts the documents filed under it")
        void countsItsDocuments() throws Exception {
            Project project = stored("Counted");
            documentRepo.save(Document.builder()
                .name("Plan").fileName("plan.pdf").fileType("application/pdf")
                .filePath("/not/read/plan.pdf").fileSize(1L)
                .documentType(Document.DocumentType.DRAWING)
                .project(project).uploadedBy(owner).build());

            mockMvc.perform(get("/api/projects/{id}", project.getId()))
                .andExpect(jsonPath("$.documentCount").value(1));
        }

        @Test
        @DisplayName("a project whose owner's record has gone still reads")
        void survivesAnAbsentOwner() throws Exception {
            // A user removed with the project retained. Failing here would make
            // the project unreachable, which is a worse outcome than an unnamed
            // owner.
            Project ownerless = projectRepo.save(Project.builder()
                .name("Ownerless " + System.nanoTime())
                .phase(Project.ProjectPhase.DESIGN).owner(null).build());

            mockMvc.perform(get("/api/projects/{id}", ownerless.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerUsername").doesNotExist());
        }
    }

    @Nested
    @DisplayName("being replaced")
    class Replacing {

        @Test
        @DisplayName("takes the details it was given")
        void replacesTheDetails() throws Exception {
            Project project = stored("Before");

            mockMvc.perform(put("/api/projects/{id}", project.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"name":"After","description":"Revised","location":"Gateshead"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("After"))
                .andExpect(jsonPath("$.location").value("Gateshead"));
        }

        @Test
        @DisplayName("clears a description that was left out — this replaces, it does not patch")
        void omittedDescriptionIsCleared() throws Exception {
            // The distinction between PUT and PATCH, and the reason a client
            // that means to change one field has to send the rest.
            Project project = stored("Before");

            mockMvc.perform(put("/api/projects/{id}", project.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"name":"After"}"""))
                .andExpect(jsonPath("$.description").doesNotExist())
                .andExpect(jsonPath("$.location").doesNotExist());
        }

        @Test
        @DisplayName("keeps the phase that was left out, which is the one exception")
        void omittedPhaseIsKept() throws Exception {
            // A project moves through phases in order, so clearing one by
            // omission would silently regress it to the start — and a regression
            // in phase changes who may see what.
            Project project = stored("Before");

            mockMvc.perform(put("/api/projects/{id}", project.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"name":"After"}"""))
                .andExpect(jsonPath("$.phase").value("DESIGN"));
        }

        @Test
        @DisplayName("moves the phase when one is named")
        void namedPhaseIsApplied() throws Exception {
            Project project = stored("Before");

            mockMvc.perform(put("/api/projects/{id}", project.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"name":"After","phase":"HANDOVER"}"""))
                .andExpect(jsonPath("$.phase").value("HANDOVER"));
        }

        @Test
        @DisplayName("does not change who owns it")
        void ownershipIsNotSettable() throws Exception {
            Project project = stored("Before");

            mockMvc.perform(put("/api/projects/{id}", project.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"name":"After","owner":"someone-else"}"""))
                .andExpect(jsonPath("$.ownerUsername").value(USERNAME));
        }

        @Test
        @DisplayName("replacing one that does not exist is a 404, not a creation")
        void replacingAnUnknownProjectIsNotFound() throws Exception {
            // A PUT to an absent id must not quietly create a project at an id
            // the client chose.
            mockMvc.perform(put("/api/projects/9999999")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"name":"Invented"}"""))
                .andExpect(status().isNotFound());

            assertThat(projectRepo.findById(9_999_999L)).isEmpty();
        }

        @Test
        @DisplayName("a replacement with no name is refused and changes nothing")
        void invalidReplacementChangesNothing() throws Exception {
            Project project = stored("Before");
            String nameBefore = project.getName();

            mockMvc.perform(put("/api/projects/{id}", project.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"description":"Revised"}"""))
                .andExpect(status().isUnprocessableContent());

            assertThat(projectRepo.findById(project.getId()).orElseThrow().getName())
                .isEqualTo(nameBefore);
        }
    }

    @Nested
    @DisplayName("being deleted")
    class Deleting {

        @Test
        @DisplayName("is gone, and reports no content rather than an empty body")
        void deletesTheProject() throws Exception {
            Project project = stored("Doomed");

            mockMvc.perform(delete("/api/projects/{id}", project.getId()))
                .andExpect(status().isNoContent());

            assertThat(projectRepo.findById(project.getId())).isEmpty();
        }

        @Test
        @DisplayName("takes its documents with it")
        void cascadesToDocuments() throws Exception {
            Project project = stored("Doomed");
            Long documentId = documentRepo.save(Document.builder()
                .name("Plan").fileName("plan.pdf").fileType("application/pdf")
                .filePath("/not/read/plan.pdf").fileSize(1L)
                .documentType(Document.DocumentType.DRAWING)
                .project(project).uploadedBy(owner).build()).getId();

            mockMvc.perform(delete("/api/projects/{id}", project.getId()))
                .andExpect(status().isNoContent());

            assertThat(documentRepo.findById(documentId))
                .as("a document left behind with no project is a row nothing can reach")
                .isEmpty();
        }

        @Test
        @DisplayName("deleting it twice reports 404 the second time, not a different failure")
        void deletionIsIdempotent() throws Exception {
            // §3.4. A client retrying after a timeout has to be able to tell
            // whether its first attempt worked, and a 500 on the repeat tells it
            // nothing.
            Project project = stored("Doomed");

            mockMvc.perform(delete("/api/projects/{id}", project.getId()))
                .andExpect(status().isNoContent());
            mockMvc.perform(delete("/api/projects/{id}", project.getId()))
                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("deleting one that never existed is a 404")
        void deletingAnUnknownProjectIsNotFound() throws Exception {
            mockMvc.perform(delete("/api/projects/9999999"))
                .andExpect(status().isNotFound());
        }
    }
}
