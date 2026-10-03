package com.cde.platform.controller;

import com.cde.platform.support.ActingAs;
import com.cde.platform.model.Document;
import com.cde.platform.model.Project;
import com.cde.platform.model.User;
import com.cde.platform.repository.DocumentRepository;
import com.cde.platform.repository.ProjectRepository;
import com.cde.platform.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the upload endpoints accept from a client, and what they refuse.
 *
 * <p>The chunk endpoint documents a `422` for an index outside the declared
 * total and had no such check. A documented behaviour that does not exist is
 * worse than an undocumented one, because a client written against it will not
 * defend itself.
 *
 * <p>The filename cases are not a traversal. Both endpoints build the storage
 * path out of the client's name, and a name containing separators is refused
 * by the filesystem rather than followed: the generated prefix makes the first
 * segment a literal directory that does not exist, so resolution stops there.
 * What the caller gets is a `500` on a perfectly ordinary filename, and what
 * the deployment gets is a storage layout one accident away from depending on
 * that prefix for its safety. The name is metadata; it should not be part of a
 * path at all.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActingAs(value = User.Role.ENGINEER, username = UploadInputHandlingTest.USERNAME)
class UploadInputHandlingTest {

    static final String USERNAME = "upload-input-user";

    /** A name carrying path separators, which a real client can send. */
    private static final String TRAVERSING_NAME = "../../../../tmp/escaped-drawing.txt";

    @Autowired MockMvc            mockMvc;
    @Autowired ProjectRepository  projectRepository;
    @Autowired UserRepository     userRepository;
    @Autowired DocumentRepository documentRepository;

    @Value("${cde.storage.upload-dir}") String uploadDir;

    private Long projectId;

    @BeforeEach
    void createAProject() {
        User owner = userRepository.findByUsername(USERNAME).orElseGet(() ->
            userRepository.save(User.builder()
                .username(USERNAME).email(USERNAME + "@example.test")
                .password("{noop}irrelevant").role(User.Role.ENGINEER).build()));

        projectId = projectRepository.save(
            Project.builder().name("Uploads " + System.nanoTime()).owner(owner).build()).getId();
    }

    private MockMultipartFile file(String field, String name) {
        return new MockMultipartFile(field, name, "text/plain",
            "some bytes".getBytes(StandardCharsets.UTF_8));
    }

    private void assertStoredInsideTheUploadRoot(long documentId) {
        Document document = documentRepository.findById(documentId).orElseThrow();
        Path root   = Path.of(uploadDir).toAbsolutePath().normalize();
        Path stored = Path.of(document.getFilePath()).toAbsolutePath().normalize();

        assertThat(stored)
            .as("the stored file belongs under %s regardless of what the client called it", root)
            .startsWith(root);

        // And the name itself is kept, sanitised, as metadata — that is what
        // it was always for.
        assertThat(document.getFileName()).doesNotContain("..");
    }

    // ── Enumerated parameters ────────────────────────────────────────────────

    /** Every regular file currently under the upload root. */
    private java.util.Set<Path> storedFiles() throws Exception {
        Path root = Path.of(uploadDir);
        if (!java.nio.file.Files.isDirectory(root)) return java.util.Set.of();
        try (var walk = java.nio.file.Files.walk(root)) {
            return walk.filter(java.nio.file.Files::isRegularFile)
                .map(each -> each.toAbsolutePath().normalize())
                .collect(java.util.stream.Collectors.toSet());
        }
    }

    @Test
    @DisplayName("an unknown document type is refused, and leaves no file behind")
    void unknownDocumentTypeLeavesNothingOnDisk() throws Exception {
        // The defect: the type was read while building the entity, which is
        // after the bytes had been streamed to quarantine, scanned, and
        // promoted to their final path. The exception then abandoned the file
        // — no row was saved, so nothing referenced it and nothing would ever
        // remove it. A typo in a parameter left rubbish on disk, and repeating
        // it filled the volume.
        var before = storedFiles();

        // The status is deliberately not asserted here. It is covered by the
        // case below, and asserting it first would mean that whenever the
        // status regressed this test failed on the status and never reached
        // the file — which is exactly how a cleanup assertion comes to be
        // believed without ever having run.
        mockMvc.perform(multipart("/api/documents/upload")
                .file(file("file", "plan.txt"))
                .param("projectId", String.valueOf(projectId))
                .param("name", "Plan")
                .param("documentType", "BLUEPRINT"));

        assertThat(storedFiles())
            .as("a refused upload must not leave a file nothing references")
            .isEqualTo(before);
    }

    @Test
    @DisplayName("the refusal names the permitted types rather than saying nothing")
    void unknownDocumentTypeSaysWhatIsAllowed() throws Exception {
        // "The request could not be processed as submitted" is what the
        // generic handler says, and it is not something a caller can act on
        // (§1.4).
        String body = mockMvc.perform(multipart("/api/documents/upload")
                .file(file("file", "plan.txt"))
                .param("projectId", String.valueOf(projectId))
                .param("name", "Plan")
                .param("documentType", "BLUEPRINT"))
            .andExpect(status().isUnprocessableContent())
            .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("BLUEPRINT").contains("DRAWING").contains("BIM_MODEL");
    }

    @Test
    @DisplayName("a document type is read whatever case it was sent in")
    void documentTypeIsCaseInsensitive() throws Exception {
        String body = mockMvc.perform(multipart("/api/documents/upload")
                .file(file("file", "spec.txt"))
                .param("projectId", String.valueOf(projectId))
                .param("name", "Spec")
                .param("documentType", "specification"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        assertThat(documentRepository.findById(idFrom(body)).orElseThrow().getDocumentType())
            .isEqualTo(Document.DocumentType.SPECIFICATION);
    }

    @Test
    @DisplayName("an upload that names no type is still accepted")
    void documentTypeDefaults() throws Exception {
        // The parameter has a default, and removing that default would turn
        // every existing client's upload into a 422.
        mockMvc.perform(multipart("/api/documents/upload")
                .file(file("file", "plan.txt"))
                .param("projectId", String.valueOf(projectId))
                .param("name", "Plan"))
            .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("an unknown status is refused, and names the permitted ones")
    void unknownStatusIsRefused() throws Exception {
        String body = mockMvc.perform(multipart("/api/documents/upload")
                .file(file("file", "plan.txt"))
                .param("projectId", String.valueOf(projectId))
                .param("name", "Plan"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        long documentId = idFrom(body);

        String refusal = mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .patch("/api/documents/" + documentId + "/status")
                    .param("status", "RESCINDED"))
            .andExpect(status().isUnprocessableContent())
            .andReturn().getResponse().getContentAsString();

        assertThat(refusal).contains("RESCINDED").contains("IN_REVIEW");
        assertThat(documentRepository.findById(documentId).orElseThrow().getStatus())
            .as("a refused status change must not have changed the status")
            .isNotNull();
    }

    private long idFrom(String body) {
        int start = body.indexOf("\"id\":") + 5;
        return Long.parseLong(body.substring(start, body.indexOf(',', start)).trim());
    }

    // ── Filenames ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a traversing filename cannot place a direct upload outside the upload root")
    void directUploadIgnoresATraversingFilename() throws Exception {
        String body = mockMvc.perform(multipart("/api/documents/upload")
                .file(file("file", TRAVERSING_NAME))
                .param("projectId", String.valueOf(projectId))
                .param("name", "Escaped"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        assertStoredInsideTheUploadRoot(idFrom(body));
    }

    @Test
    @DisplayName("a traversing filename cannot place a chunked upload outside the upload root")
    void chunkedUploadIgnoresATraversingFilename() throws Exception {
        String body = mockMvc.perform(multipart("/api/documents/upload/chunk")
                .file(file("chunk", "part.bin"))
                .param("uploadId", "b6f2c8d1-4a37-4e59-9c02-1f8b7d3e5a64")
                .param("chunkIndex", "0")
                .param("totalChunks", "1")
                .param("fileName", TRAVERSING_NAME)
                .param("projectId", String.valueOf(projectId)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        assertStoredInsideTheUploadRoot(idFrom(body));
    }

    // ── Chunk indices ────────────────────────────────────────────────────────

    @Test
    @DisplayName("a chunk index outside the declared total is refused")
    void anIndexBeyondTheTotalIsRefused() throws Exception {
        // Documented as a 422 since the specification was written. Unchecked,
        // it was accepted and stored, which is how a foreign index came to sit
        // in somebody else's upload.
        mockMvc.perform(multipart("/api/documents/upload/chunk")
                .file(file("chunk", "part.bin"))
                .param("uploadId", "c1d9e7f3-5b24-4a86-8f10-3e6c2b9a7d51")
                .param("chunkIndex", "9")
                .param("totalChunks", "2")
                .param("fileName", "big.bin"))
            .andExpect(status().isUnprocessableContent())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .jsonPath("$.detail").exists());
    }

    @Test
    @DisplayName("a negative chunk index is refused")
    void aNegativeIndexIsRefused() throws Exception {
        mockMvc.perform(multipart("/api/documents/upload/chunk")
                .file(file("chunk", "part.bin"))
                .param("uploadId", "e4a1b8c6-9d37-4520-a1f8-6b3d0c7e2f95")
                .param("chunkIndex", "-1")
                .param("totalChunks", "2")
                .param("fileName", "big.bin"))
            .andExpect(status().isUnprocessableContent());
    }

    @Test
    @DisplayName("an implausible chunk count is refused rather than reserved for")
    void anAbsurdTotalIsRefused() throws Exception {
        // Unbounded, this is the cheapest denial of service available: one
        // request declares a huge upload and the server makes room for it.
        mockMvc.perform(multipart("/api/documents/upload/chunk")
                .file(file("chunk", "part.bin"))
                .param("uploadId", "f7c3d2e9-1a58-4b64-9e07-5d2a8f1c3b46")
                .param("chunkIndex", "0")
                .param("totalChunks", "100000000")
                .param("fileName", "big.bin"))
            .andExpect(status().isUnprocessableContent());
    }

    // ── Stored active content ─────────────────────────────────────────────

    @Test
    @DisplayName("an SVG is kept as markup the viewer can render without a converter")
    void svgIsKeptInline() throws Exception {
        // A drawing the viewer renders as markup rather than fetching back off
        // disk each time. The bound on it is what stops this being a way to put
        // an arbitrary amount of a file into a database column.
        String body = mockMvc.perform(multipart("/api/documents/upload")
                .file(new MockMultipartFile("file", "plan.svg", "image/svg+xml",
                    "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect/></svg>"
                        .getBytes(StandardCharsets.UTF_8)))
                .param("projectId", String.valueOf(projectId))
                .param("name", "Plan"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        assertThat(documentRepository.findById(idFrom(body)).orElseThrow().getVectorData())
            .contains("<svg");
    }

    @Test
    @DisplayName("a file claiming to be an SVG but carrying no SVG is not kept as markup")
    void nonSvgContentIsNotKeptInline() throws Exception {
        // §5.13.9 treats an SVG as active content, and the viewer renders this
        // field. Storing whatever arrived under an SVG media type would make
        // the content type — which the client chooses — the only thing deciding
        // what gets rendered.
        String body = mockMvc.perform(multipart("/api/documents/upload")
                .file(new MockMultipartFile("file", "plan.svg", "image/svg+xml",
                    "this is not markup at all".getBytes(StandardCharsets.UTF_8)))
                .param("projectId", String.valueOf(projectId))
                .param("name", "Plan"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        assertThat(documentRepository.findById(idFrom(body)).orElseThrow().getVectorData())
            .isNull();
    }

    @Test
    @DisplayName("an uppercase SVG root is recognised as markup too")
    void uppercaseSvgIsRecognised() throws Exception {
        String body = mockMvc.perform(multipart("/api/documents/upload")
                .file(new MockMultipartFile("file", "plan.svg", "image/svg+xml",
                    "<SVG xmlns=\"http://www.w3.org/2000/svg\"></SVG>"
                        .getBytes(StandardCharsets.UTF_8)))
                .param("projectId", String.valueOf(projectId))
                .param("name", "Plan"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        assertThat(documentRepository.findById(idFrom(body)).orElseThrow().getVectorData())
            .isNotNull();
    }

    @Test
    @DisplayName("a document that is not an SVG keeps nothing inline")
    void nonSvgKeepsNothingInline() throws Exception {
        String body = mockMvc.perform(multipart("/api/documents/upload")
                .file(file("file", "plan.pdf"))
                .param("projectId", String.valueOf(projectId))
                .param("name", "Plan"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        assertThat(documentRepository.findById(idFrom(body)).orElseThrow().getVectorData())
            .isNull();
    }

    // ── What the reply says about a document ──────────────────────────────

    @Test
    @DisplayName("the reply names the project the document was filed under")
    void replyNamesTheProject() throws Exception {
        String body = mockMvc.perform(multipart("/api/documents/upload")
                .file(file("file", "plan.pdf"))
                .param("projectId", String.valueOf(projectId))
                .param("name", "Plan"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"projectId\":" + projectId);
    }

    @Test
    @DisplayName("the reply names who uploaded it rather than exposing their record")
    void replyNamesTheUploader() throws Exception {
        // A username, not a user id and not an object: §5.13.13 rules out
        // handing out enumerable identifiers, and nothing on this screen needs
        // more than the name.
        String body = mockMvc.perform(multipart("/api/documents/upload")
                .file(file("file", "plan.pdf"))
                .param("projectId", String.valueOf(projectId))
                .param("name", "Plan"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        assertThat(body).contains(USERNAME);
    }

    @Test
    @DisplayName("a blank status is refused with an instruction, not an enum list")
    void blankStatusIsRefused() throws Exception {
        // Sending the parameter empty is a different mistake from sending a
        // value that is not a status, and listing the permitted values at
        // someone who sent nothing does not tell them what went wrong.
        String body = mockMvc.perform(multipart("/api/documents/upload")
                .file(file("file", "plan.txt"))
                .param("projectId", String.valueOf(projectId))
                .param("name", "Plan"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        String refusal = mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .patch("/api/documents/" + idFrom(body) + "/status")
                    .param("status", "   "))
            .andExpect(status().isUnprocessableContent())
            .andReturn().getResponse().getContentAsString();

        assertThat(refusal).contains("Name the status");
    }

    @Test
    @DisplayName("a status is read whatever case it was sent in")
    void statusIsCaseInsensitive() throws Exception {
        String body = mockMvc.perform(multipart("/api/documents/upload")
                .file(file("file", "plan.txt"))
                .param("projectId", String.valueOf(projectId))
                .param("name", "Plan"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        long documentId = idFrom(body);

        mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .patch("/api/documents/" + documentId + "/status")
                    .param("status", "in_review"))
            .andExpect(status().isOk());

        assertThat(documentRepository.findById(documentId).orElseThrow().getStatus())
            .isEqualTo(Document.DocumentStatus.IN_REVIEW);
    }

    @Test
    @DisplayName("changing the status of a document that does not exist is a 404")
    void statusChangeOnAnUnknownDocument() throws Exception {
        mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .patch("/api/documents/9999999/status")
                    .param("status", "IN_REVIEW"))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the reply survives a document with no project or uploader recorded")
    void replySurvivesAnIncompleteDocument() throws Exception {
        // Rows like this exist: a project deleted, or a user removed, with the
        // document retained for the audit trail. A status change on one must
        // answer rather than throw, because the alternative is a record nobody
        // can move on.
        Document orphan = documentRepository.save(Document.builder()
            .name("Orphan").fileName("orphan.txt").fileType("text/plain")
            .filePath("/not/read/by/this/endpoint/orphan.txt").fileSize(1L)
            .documentType(Document.DocumentType.DRAWING)
            .project(null).uploadedBy(null).build());

        mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .patch("/api/documents/" + orphan.getId() + "/status")
                    .param("status", "APPROVED"))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .jsonPath("$.projectId").doesNotExist())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .jsonPath("$.uploadedBy").doesNotExist());
    }

    @Test
    @DisplayName("an unknown document type on a chunked upload is refused before assembly")
    void unknownTypeOnAChunkedUploadIsRefused() throws Exception {
        // The chunked route carries the largest files, so the orphan a late
        // refusal would leave behind is the largest too.
        String uploadId = "chunked-" + System.nanoTime();

        mockMvc.perform(multipart("/api/documents/upload/chunk")
                .file(new MockMultipartFile("chunk", "part", "application/octet-stream",
                    "some bytes".getBytes(StandardCharsets.UTF_8)))
                .param("uploadId", uploadId)
                .param("chunkIndex", "0")
                .param("totalChunks", "1")
                .param("fileName", "model.ifc")
                .param("projectId", String.valueOf(projectId))
                .param("documentType", "NOT_A_TYPE"))
            .andExpect(status().isUnprocessableContent());
    }

    @Test
    @DisplayName("the last chunk without a project is acknowledged, not assembled")
    void lastChunkWithoutAProjectIsOnlyAcknowledged() throws Exception {
        // A client that uploads the bytes first and names the project on a
        // later call. Assembling without one would have nowhere to file the
        // document, so the count comes back and the chunks stay staged.
        String uploadId = "pending-" + System.nanoTime();

        mockMvc.perform(multipart("/api/documents/upload/chunk")
                .file(new MockMultipartFile("chunk", "part", "application/octet-stream",
                    "some bytes".getBytes(StandardCharsets.UTF_8)))
                .param("uploadId", uploadId)
                .param("chunkIndex", "0")
                .param("totalChunks", "1")
                .param("fileName", "model.ifc"))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .jsonPath("$.received").value(1))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .jsonPath("$.documentId").doesNotExist());
    }

    @Test
    @DisplayName("a chunked upload that completes files the document under its project")
    void completedChunkedUploadIsFiled() throws Exception {
        String uploadId = "complete-" + System.nanoTime();

        mockMvc.perform(multipart("/api/documents/upload/chunk")
                .file(new MockMultipartFile("chunk", "part", "application/octet-stream",
                    "ISO-10303-21;".getBytes(StandardCharsets.UTF_8)))
                .param("uploadId", uploadId)
                .param("chunkIndex", "0")
                .param("totalChunks", "1")
                .param("fileName", "model.ifc")
                .param("projectId", String.valueOf(projectId)))
            .andExpect(status().isCreated())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .jsonPath("$.fileName").value("model.ifc"));
    }
}
