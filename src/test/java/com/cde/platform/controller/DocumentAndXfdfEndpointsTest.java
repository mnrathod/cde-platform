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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reading and deleting a document, and the XFDF round trip beside it.
 *
 * <p>XFDF is the interchange format every other reviewing tool speaks, so
 * these two endpoints are how markup gets in and out of the product. The
 * import is also an XML upload, which §5.13.9 classes as active content — so
 * the case that matters most here is the one that proves external entities
 * are not resolved. That test has to fail if the parser is ever relaxed,
 * which is why it asks for a file the parser could actually read rather than
 * an unreachable one: a missing file yields nothing either way and would
 * pass against a wide-open parser.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("documents and XFDF interchange")
class DocumentAndXfdfEndpointsTest {

    private static final String USERNAME = "xfdf-endpoints-user";

    @Autowired MockMvc mockMvc;
    @Autowired AnnotationRepository annotationRepo;
    @Autowired DocumentRepository documentRepo;
    @Autowired ProjectRepository projectRepo;
    @Autowired UserRepository userRepo;

    private Project project;
    private User owner;
    private Document sheet;

    @BeforeEach
    void setUp() {
        owner = userRepo.findByUsername(USERNAME).orElseGet(() ->
            userRepo.save(User.builder()
                .username(USERNAME).email("xfdf-endpoints@example.com")
                .password("x").role(User.Role.ENGINEER).build()));

        project = projectRepo.save(Project.builder()
            .name("Interchange").description("d")
            .phase(Project.ProjectPhase.DESIGN).build());

        sheet = documentRepo.save(Document.builder()
            .name("Ground floor").fileName("A-101.pdf").fileType("application/pdf")
            .filePath("/tmp/A-101.pdf")
            .documentType(Document.DocumentType.DRAWING)
            .drawingNumber("A-101").revision("P02")
            .project(project).uploadedBy(owner).build());
    }

    private Annotation markup(String comment) {
        return annotationRepo.save(Annotation.builder()
            .document(sheet).author(owner)
            .type(Annotation.AnnotationType.HIGHLIGHT)
            .shapeData("{\"x\":10,\"y\":20,\"w\":30,\"h\":40}")
            .comment(comment).pageNumber(1)
            .status(Annotation.AnnotationStatus.OPEN)
            .createdAt(LocalDateTime.now())
            .build());
    }

    private MockMultipartFile xfdfFile(String body) {
        return new MockMultipartFile("file", "markup.xfdf", "application/vnd.adobe.xfdf",
                                     body.getBytes(StandardCharsets.UTF_8));
    }

    // ── Reading a document ────────────────────────────────────────────────

    @Nested
    @DisplayName("reading a document")
    class Reading {

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("returns its metadata")
        void returnsMetadata() throws Exception {
            mockMvc.perform(get("/api/documents/{id}", sheet.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Ground floor"))
                .andExpect(jsonPath("$.drawingNumber").value("A-101"))
                .andExpect(jsonPath("$.revision").value("P02"));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("names who uploaded it rather than exposing their record")
        void namesTheUploader() throws Exception {
            mockMvc.perform(get("/api/documents/{id}", sheet.getId()))
                .andExpect(jsonPath("$.uploadedBy").value(USERNAME));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("does not include the path the file is stored at")
        void doesNotLeakTheStoragePath() throws Exception {
            // §5.13.13: a storage path in a response is an invitation to
            // guess the next one.
            mockMvc.perform(get("/api/documents/{id}", sheet.getId()))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                    org.hamcrest.Matchers.containsString("/tmp/"))));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("a document that does not exist is a 404")
        void unknownDocumentIsNotFound() throws Exception {
            mockMvc.perform(get("/api/documents/{id}", 9_999_999L))
                .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("listing a project's documents")
    class Listing {

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("returns a page, not a bare array")
        void returnsAPage() throws Exception {
            mockMvc.perform(get("/api/documents/project/{id}", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").exists());
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("honours the page size asked for")
        void honoursPageSize() throws Exception {
            documentRepo.save(Document.builder()
                .name("First floor").fileName("A-102.pdf").fileType("application/pdf")
                .filePath("/tmp/A-102.pdf").documentType(Document.DocumentType.DRAWING)
                .project(project).uploadedBy(owner).build());

            mockMvc.perform(get("/api/documents/project/{id}?size=1", project.getId()))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(2));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("an empty project lists nothing rather than failing")
        void emptyProjectListsNothing() throws Exception {
            Project empty = projectRepo.save(Project.builder()
                .name("Empty").description("d")
                .phase(Project.ProjectPhase.DESIGN).build());

            mockMvc.perform(get("/api/documents/project/{id}", empty.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
        }
    }

    // ── Deleting ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("deleting a document")
    class Deleting {

        @Test
        @WithMockUser(username = USERNAME, roles = "ADMIN")
        @DisplayName("takes its markup with it")
        void cascadesToMarkup() throws Exception {
            // Annotations reference the document with a non-null,
            // non-cascading foreign key, so a direct delete fails on the
            // constraint — which is why this is delegated rather than done
            // in the repository.
            Annotation attached = markup("Check this");

            mockMvc.perform(delete("/api/documents/{id}", sheet.getId()).with(csrf()))
                .andExpect(status().isNoContent());

            assertThat(documentRepo.findById(sheet.getId())).isEmpty();
            assertThat(annotationRepo.findById(attached.getId())).isEmpty();
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ADMIN")
        @DisplayName("deleting one that does not exist is a 404, not a silent success")
        void deletingUnknownDocumentIsNotFound() throws Exception {
            mockMvc.perform(delete("/api/documents/{id}", 9_999_999L).with(csrf()))
                .andExpect(status().isNotFound());
        }
    }

    // ── Exporting markup ──────────────────────────────────────────────────

    @Nested
    @DisplayName("exporting markup as XFDF")
    class Exporting {

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("produces an XFDF document naming the file it belongs to")
        void producesXfdf() throws Exception {
            markup("Check this dimension");

            mockMvc.perform(get("/api/annotations/document/{id}/xfdf", sheet.getId()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/vnd.adobe.xfdf"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("A-101.pdf")));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("offers it as a download named after the document")
        void offersADownload() throws Exception {
            mockMvc.perform(get("/api/annotations/document/{id}/xfdf", sheet.getId()))
                .andExpect(header().string("Content-Disposition",
                                           "attachment; filename=\"Ground floor.xfdf\""));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("carries the markup's own note")
        void carriesTheNote() throws Exception {
            markup("Check this dimension");

            mockMvc.perform(get("/api/annotations/document/{id}/xfdf", sheet.getId()))
                .andExpect(content().string(
                    org.hamcrest.Matchers.containsString("Check this dimension")));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("a document with no markup exports an empty set, not an error")
        void emptyExportIsValid() throws Exception {
            // A reviewing tool asked for the markup and there is none. That
            // is an answer, not a failure.
            mockMvc.perform(get("/api/annotations/document/{id}/xfdf", sheet.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("xfdf")));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("a document that does not exist is a 404")
        void unknownDocumentIsNotFound() throws Exception {
            mockMvc.perform(get("/api/annotations/document/{id}/xfdf", 9_999_999L))
                .andExpect(status().isNotFound());
        }
    }

    // ── Importing markup ──────────────────────────────────────────────────

    @Nested
    @DisplayName("importing markup from XFDF")
    class Importing {

        private String xfdfWith(String annotations) {
            return """
                <?xml version="1.0" encoding="UTF-8"?>
                <xfdf xmlns="http://ns.adobe.com/xfdf/">
                  <annots>%s</annots>
                  <f href="A-101.pdf"/>
                </xfdf>""".formatted(annotations);
        }

        private String highlight(String contents) {
            return """
                <highlight page="0" rect="10,10,50,50" color="#FFFF00">
                  <contents>%s</contents>
                </highlight>""".formatted(contents);
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("adds the file's markup to the document")
        void importsMarkup() throws Exception {
            mockMvc.perform(multipart("/api/annotations/document/{id}/xfdf", sheet.getId())
                    .file(xfdfFile(xfdfWith(highlight("From another tool")))).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("adds to the markup already there rather than replacing it")
        void importAdds() throws Exception {
            // Importing adds. A replace would discard a colleague's review
            // because somebody opened the drawing in another tool.
            markup("Already here");

            mockMvc.perform(multipart("/api/annotations/document/{id}/xfdf", sheet.getId())
                    .file(xfdfFile(xfdfWith(highlight("Newly arrived")))).with(csrf()))
                .andExpect(status().isOk());

            assertThat(annotationRepo.findByDocument_Id(sheet.getId())).hasSize(2);
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("attributes imported markup to the caller, not to the file")
        void importedMarkupIsAttributedToTheCaller() throws Exception {
            // The file says who wrote it in the originating tool, and that
            // is not an identity this platform can vouch for.
            mockMvc.perform(multipart("/api/annotations/document/{id}/xfdf", sheet.getId())
                    .file(xfdfFile(xfdfWith(highlight("From another tool")))).with(csrf()))
                .andExpect(status().isOk());

            // Read back through the API rather than the repository: the
            // author is a lazy association, and the point being asserted is
            // what a client is told, not what the row holds.
            mockMvc.perform(get("/api/annotations/document/{id}", sheet.getId()))
                .andExpect(jsonPath("$[0].author").value(USERNAME));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("an empty file is reported as empty, not as rejected")
        void emptyFileIsNotAnError() throws Exception {
            // "The file was empty" and "the file was rejected" call for
            // different things from whoever chose it.
            mockMvc.perform(multipart("/api/annotations/document/{id}/xfdf", sheet.getId())
                    .file(xfdfFile(xfdfWith(""))).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(0))
                .andExpect(jsonPath("$.message")
                    .value(org.hamcrest.Matchers.containsString("no markup")));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("does not resolve an external entity (§5.13.9)")
        void externalEntitiesAreNotResolved() throws Exception {
            // An XML upload is active content. The entity below points at a
            // file that exists and is readable, so a parser with external
            // entities enabled would pull its contents into the note — which
            // is how an XFDF import becomes a file read of the host.
            String withEntity = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE xfdf [<!ENTITY leak SYSTEM "file:///etc/hostname">]>
                <xfdf xmlns="http://ns.adobe.com/xfdf/">
                  <annots>
                    <highlight page="0" rect="10,10,50,50" color="#FFFF00">
                      <contents>&leak;</contents>
                    </highlight>
                  </annots>
                  <f href="A-101.pdf"/>
                </xfdf>""";

            mockMvc.perform(multipart("/api/annotations/document/{id}/xfdf", sheet.getId())
                .file(xfdfFile(withEntity)).with(csrf()));

            assertThat(annotationRepo.findByDocument_Id(sheet.getId()))
                .allSatisfy(annotation -> assertThat(annotation.getComment())
                    .doesNotContain(java.nio.file.Files.exists(java.nio.file.Path.of("/etc/hostname"))
                        ? java.nio.file.Files.readString(java.nio.file.Path.of("/etc/hostname")).trim()
                        : "unreachable-sentinel"));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("importing onto a document that does not exist is a 404")
        void unknownDocumentIsNotFound() throws Exception {
            mockMvc.perform(multipart("/api/annotations/document/{id}/xfdf", 9_999_999L)
                    .file(xfdfFile(xfdfWith(highlight("nowhere")))).with(csrf()))
                .andExpect(status().isNotFound());
        }
    }
}
