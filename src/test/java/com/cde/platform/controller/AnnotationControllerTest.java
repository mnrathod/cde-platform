package com.cde.platform.controller;

import com.cde.platform.model.Annotation;
import com.cde.platform.model.Document;
import com.cde.platform.model.Project;
import com.cde.platform.model.User;
import com.cde.platform.repository.AnnotationRepository;
import com.cde.platform.repository.DocumentRepository;
import com.cde.platform.repository.ProjectRepository;
import com.cde.platform.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Markup on a document, and the thread hanging off it.
 *
 * <p>Two rules run through all of it. The author is the authenticated caller
 * and is never taken from the request body — §5.12's mass-assignment rule,
 * and the difference between a review record and a forgeable one. And the
 * fields fixed at creation stay fixed: markup that changed which document or
 * page it pointed at would invalidate every reply already written against
 * it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("markup on a document")
class AnnotationControllerTest {

    private static final String AUTHOR = "annotation-author";
    private static final String OTHER = "annotation-other";

    @Autowired MockMvc mockMvc;
    @Autowired AnnotationRepository annotationRepo;
    @Autowired DocumentRepository documentRepo;
    @Autowired ProjectRepository projectRepo;
    @Autowired UserRepository userRepo;

    private Document sheet;

    @BeforeEach
    void setUp() {
        User author = user(AUTHOR);
        user(OTHER);

        Project project = projectRepo.save(Project.builder()
            .name("Markup").description("d")
            .phase(Project.ProjectPhase.DESIGN).build());

        sheet = documentRepo.save(Document.builder()
            .name("Sheet").fileName("sheet.pdf").fileType("application/pdf")
            .filePath("/tmp/sheet.pdf")
            .documentType(Document.DocumentType.DRAWING)
            .project(project).uploadedBy(author).build());
    }

    private User user(String username) {
        return userRepo.findByUsername(username).orElseGet(() ->
            userRepo.save(User.builder()
                .username(username).email(username + "@example.com")
                .password("x").role(User.Role.ENGINEER).build()));
    }

    private Annotation existingMarkup() {
        return annotationRepo.save(Annotation.builder()
            .document(sheet).author(user(AUTHOR))
            .type(Annotation.AnnotationType.HIGHLIGHT)
            .shapeData("{\"x\":10,\"y\":10}")
            .comment("Check this dimension")
            .pageNumber(2)
            .status(Annotation.AnnotationStatus.OPEN)
            .createdAt(LocalDateTime.now())
            .build());
    }

    private String createBody(String shapeData, int pageNumber) {
        return """
            {"documentId":%d,"type":"HIGHLIGHT","shapeData":%s,"comment":"a note","pageNumber":%d}"""
            .formatted(sheet.getId(),
                       shapeData == null ? "null" : "\"" + shapeData + "\"",
                       pageNumber);
    }

    // ── Creating ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("placing markup")
    class Creating {

        @Test
        @WithMockUser(username = AUTHOR, roles = "ENGINEER")
        @DisplayName("returns 201 with the markup as created")
        void createsMarkup() throws Exception {
            mockMvc.perform(post("/api/annotations").with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody("{\\\"x\\\":1}", 3)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pageNumber").value(3))
                .andExpect(jsonPath("$.status").value("OPEN"));
        }

        @Test
        @WithMockUser(username = AUTHOR, roles = "ENGINEER")
        @DisplayName("attributes it to the authenticated caller")
        void authorIsTheCaller() throws Exception {
            // Never taken from the body. An author a client can choose is an
            // author anybody can forge, and this is a review record.
            mockMvc.perform(post("/api/annotations").with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody("{\\\"x\\\":1}", 1)))
                .andExpect(jsonPath("$.author").value(AUTHOR));
        }

        @Test
        @WithMockUser(username = OTHER, roles = "ENGINEER")
        @DisplayName("attributes it to whoever is signed in, not to the document's owner")
        void authorIsNotTheDocumentOwner() throws Exception {
            mockMvc.perform(post("/api/annotations").with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody("{\\\"x\\\":1}", 1)))
                .andExpect(jsonPath("$.author").value(OTHER));
        }

        @Test
        @WithMockUser(username = AUTHOR, roles = "ENGINEER")
        @DisplayName("starts open, whatever the request said")
        void startsOpen() throws Exception {
            mockMvc.perform(post("/api/annotations").with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"documentId":%d,"type":"HIGHLIGHT","shapeData":"{}","comment":"n",
                         "pageNumber":1,"status":"RESOLVED"}""".formatted(sheet.getId())))
                .andExpect(jsonPath("$.status").value("OPEN"));
        }

        @Test
        @WithMockUser(username = AUTHOR, roles = "ENGINEER")
        @DisplayName("refuses markup with no shape")
        void refusesMissingShape() throws Exception {
            mockMvc.perform(post("/api/annotations").with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody(null, 1)))
                .andExpect(status().isUnprocessableContent());
        }

        @Test
        @WithMockUser(username = AUTHOR, roles = "ENGINEER")
        @DisplayName("refuses a page number below one")
        void refusesPageZero() throws Exception {
            // There is no page 0, and markup anchored there can never be
            // shown or found again.
            mockMvc.perform(post("/api/annotations").with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody("{\\\"x\\\":1}", 0)))
                .andExpect(status().isUnprocessableContent());
        }

        @Test
        @WithMockUser(username = AUTHOR, roles = "ENGINEER")
        @DisplayName("refuses markup on a document that does not exist")
        void refusesUnknownDocument() throws Exception {
            mockMvc.perform(post("/api/annotations").with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"documentId":9999999,"type":"HIGHLIGHT","shapeData":"{}",
                         "comment":"n","pageNumber":1}"""))
                .andExpect(status().isNotFound());
        }
    }

    // ── Reading ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("listing markup")
    class Listing {

        @Test
        @WithMockUser(username = AUTHOR, roles = "ENGINEER")
        @DisplayName("returns everything on the document")
        void listsMarkup() throws Exception {
            existingMarkup();

            mockMvc.perform(get("/api/annotations/document/{id}", sheet.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].comment").value("Check this dimension"));
        }

        @Test
        @WithMockUser(username = AUTHOR, roles = "ENGINEER")
        @DisplayName("includes resolved markup as well as open")
        void includesResolved() throws Exception {
            // Filtering is the client's job: a review panel usually wants
            // both, with the resolved ones collapsed.
            Annotation resolved = existingMarkup();
            resolved.setStatus(Annotation.AnnotationStatus.RESOLVED);
            annotationRepo.save(resolved);

            mockMvc.perform(get("/api/annotations/document/{id}", sheet.getId()))
                .andExpect(jsonPath("$.length()").value(1));
        }

        @Test
        @WithMockUser(username = AUTHOR, roles = "ENGINEER")
        @DisplayName("returns an empty list for a document nobody has marked up")
        void emptyDocumentIsEmptyList() throws Exception {
            mockMvc.perform(get("/api/annotations/document/{id}", sheet.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        }
    }

    // ── Changing ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("changing markup")
    class Updating {

        @Test
        @WithMockUser(username = AUTHOR, roles = "ENGINEER")
        @DisplayName("changes the shape and the note")
        void updatesShapeAndComment() throws Exception {
            Annotation markup = existingMarkup();

            mockMvc.perform(put("/api/annotations/{id}", markup.getId()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"documentId":%d,"type":"HIGHLIGHT","shapeData":"{\\"x\\":99}",
                         "comment":"Moved","pageNumber":2}""".formatted(sheet.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comment").value("Moved"));
        }

        @Test
        @WithMockUser(username = AUTHOR, roles = "ENGINEER")
        @DisplayName("will not move markup to another page")
        void pageIsFixedAtCreation() throws Exception {
            // Every reply already written against it refers to what is on
            // that page. Moving it silently detaches the whole thread from
            // what it was about.
            Annotation markup = existingMarkup();

            mockMvc.perform(put("/api/annotations/{id}", markup.getId()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"documentId":%d,"type":"HIGHLIGHT","shapeData":"{}",
                         "comment":"n","pageNumber":7}""".formatted(sheet.getId())))
                .andExpect(jsonPath("$.pageNumber").value(2));
        }

        @Test
        @WithMockUser(username = AUTHOR, roles = "ENGINEER")
        @DisplayName("will not reattach markup to another document")
        void documentIsFixedAtCreation() throws Exception {
            Annotation markup = existingMarkup();

            mockMvc.perform(put("/api/annotations/{id}", markup.getId()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"documentId":9999999,"type":"HIGHLIGHT","shapeData":"{}",
                         "comment":"n","pageNumber":2}"""))
                .andExpect(jsonPath("$.documentId").value(sheet.getId()));
        }

        @Test
        @WithMockUser(username = AUTHOR, roles = "ENGINEER")
        @DisplayName("changing markup that does not exist is a 404")
        void updatingUnknownMarkupIsNotFound() throws Exception {
            mockMvc.perform(put("/api/annotations/{id}", 9_999_999L).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"documentId":1,"type":"HIGHLIGHT","shapeData":"{}",
                         "comment":"n","pageNumber":1}"""))
                .andExpect(status().isNotFound());
        }
    }

    // ── Resolving ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("resolving markup")
    class Resolving {

        @Test
        @WithMockUser(username = AUTHOR, roles = "ENGINEER")
        @DisplayName("marks it resolved")
        void resolves() throws Exception {
            Annotation markup = existingMarkup();

            mockMvc.perform(patch("/api/annotations/{id}/resolve", markup.getId()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));
        }

        @Test
        @WithMockUser(username = AUTHOR, roles = "ENGINEER")
        @DisplayName("resolving twice succeeds and changes nothing")
        void resolvingIsIdempotent() throws Exception {
            Annotation markup = existingMarkup();
            mockMvc.perform(patch("/api/annotations/{id}/resolve", markup.getId()).with(csrf()));

            mockMvc.perform(patch("/api/annotations/{id}/resolve", markup.getId()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));
        }

        @Test
        @WithMockUser(username = AUTHOR, roles = "ENGINEER")
        @DisplayName("keeps the markup and its note in place")
        void resolvingDoesNotErase() throws Exception {
            // Resolving records that the point was addressed. It does not
            // remove the record that it was raised.
            Annotation markup = existingMarkup();

            mockMvc.perform(patch("/api/annotations/{id}/resolve", markup.getId()).with(csrf()))
                .andExpect(jsonPath("$.comment").value("Check this dimension"));
            assertThat(annotationRepo.findById(markup.getId())).isPresent();
        }

        @Test
        @WithMockUser(username = AUTHOR, roles = "ENGINEER")
        @DisplayName("resolving markup that does not exist is a 404")
        void resolvingUnknownMarkupIsNotFound() throws Exception {
            mockMvc.perform(patch("/api/annotations/{id}/resolve", 9_999_999L).with(csrf()))
                .andExpect(status().isNotFound());
        }
    }

    // ── Deleting ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("deleting markup")
    class Deleting {

        @Test
        @WithMockUser(username = AUTHOR, roles = "ENGINEER")
        @DisplayName("removes it")
        void deletes() throws Exception {
            Annotation markup = existingMarkup();

            mockMvc.perform(delete("/api/annotations/{id}", markup.getId()).with(csrf()))
                .andExpect(status().isNoContent());

            assertThat(annotationRepo.findById(markup.getId())).isEmpty();
        }

        @Test
        @WithMockUser(username = AUTHOR, roles = "ENGINEER")
        @DisplayName("deleting markup that does not exist is a 404, not a silent success")
        void deletingUnknownMarkupIsNotFound() throws Exception {
            // A 204 here would tell a client its markup had been removed
            // when nothing of the kind happened.
            mockMvc.perform(delete("/api/annotations/{id}", 9_999_999L).with(csrf()))
                .andExpect(status().isNotFound());
        }
    }

    // ── The reply thread ──────────────────────────────────────────────────

    @Nested
    @DisplayName("the reply thread")
    class Replies {

        private String replyBody(String content) {
            return "{\"content\":\"" + content + "\"}";
        }

        @Test
        @WithMockUser(username = OTHER, roles = "ENGINEER")
        @DisplayName("a reply is attributed to whoever wrote it")
        void replyIsAttributedToItsAuthor() throws Exception {
            Annotation markup = existingMarkup();

            mockMvc.perform(post("/api/annotations/{id}/replies", markup.getId()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(replyBody("Agreed, will amend")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.authorName").value(OTHER));
        }

        @Test
        @WithMockUser(username = AUTHOR, roles = "ENGINEER")
        @DisplayName("replies come back in the thread")
        void repliesAreListed() throws Exception {
            Annotation markup = existingMarkup();
            mockMvc.perform(post("/api/annotations/{id}/replies", markup.getId()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(replyBody("First")));

            mockMvc.perform(get("/api/annotations/{id}/replies", markup.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].content").value("First"));
        }

        @Test
        @WithMockUser(username = AUTHOR, roles = "ENGINEER")
        @DisplayName("an empty reply is refused")
        void refusesAnEmptyReply() throws Exception {
            Annotation markup = existingMarkup();

            mockMvc.perform(post("/api/annotations/{id}/replies", markup.getId()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(replyBody("")))
                .andExpect(status().isUnprocessableContent());
        }

        @Test
        @WithMockUser(username = AUTHOR, roles = "ENGINEER")
        @DisplayName("replying to markup that does not exist is a 404")
        void replyingToUnknownMarkupIsNotFound() throws Exception {
            mockMvc.perform(post("/api/annotations/{id}/replies", 9_999_999L).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(replyBody("Into the void")))
                .andExpect(status().isNotFound());
        }

        @Test
        @WithMockUser(username = AUTHOR, roles = "ENGINEER")
        @DisplayName("deleting a reply leaves the markup and the rest of the thread")
        void deletingAReplyLeavesTheRest() throws Exception {
            Annotation markup = existingMarkup();
            String created = mockMvc.perform(
                    post("/api/annotations/{id}/replies", markup.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(replyBody("First")))
                .andReturn().getResponse().getContentAsString();
            mockMvc.perform(post("/api/annotations/{id}/replies", markup.getId()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(replyBody("Second")));
            long replyId = Long.parseLong(created.replaceAll(".*\"id\":(\\d+).*", "$1"));

            mockMvc.perform(delete("/api/annotations/replies/{id}", replyId).with(csrf()))
                .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/annotations/{id}/replies", markup.getId()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].content").value("Second"));
            assertThat(annotationRepo.findById(markup.getId())).isPresent();
        }

        @Test
        @WithMockUser(username = AUTHOR, roles = "ENGINEER")
        @DisplayName("deleting a reply that does not exist is a 404")
        void deletingUnknownReplyIsNotFound() throws Exception {
            mockMvc.perform(delete("/api/annotations/replies/{id}", 9_999_999L).with(csrf()))
                .andExpect(status().isNotFound());
        }
    }
}
