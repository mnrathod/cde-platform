package com.cde.platform.service;

import com.cde.platform.exception.DocumentProcessingException;
import com.cde.platform.model.Document;
import com.cde.platform.model.Project;
import com.cde.platform.model.User;
import com.cde.platform.repository.DocumentRepository;
import com.cde.platform.repository.ProjectRepository;
import com.cde.platform.repository.UserRepository;
import com.cde.platform.service.DocumentProcessingService.TextSearch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * The operations that rewrite a document, and what they do when they fail.
 *
 * <p>All of them share one pipeline — allocate a work file, call the
 * converter, commit a version — and the rules that pipeline enforces are the
 * ones worth holding: a version is committed only after the converter has
 * reported success, and the work file is removed on every path that does not
 * commit. A leaked work file per failed redaction fills a disk quietly,
 * which is the kind of fault that is noticed months later by someone else.
 *
 * <p>The converter is stubbed. It is a separate process reached over HTTP
 * (§5.13.10) and what is under test is what this service does with its
 * answers, including the answers that say no.
 */
@SpringBootTest
@DisplayName("processing a document")
class DocumentProcessingServiceTest {

    private static final String USERNAME = "processing-service-user";

    @Autowired DocumentProcessingService processing;
    @Autowired DocumentRepository documentRepo;
    @Autowired ProjectRepository projectRepo;
    @Autowired UserRepository userRepo;

    @MockitoBean ConverterService converter;

    @TempDir Path storage;

    private final ObjectMapper mapper = new ObjectMapper();
    private Document document;

    @BeforeEach
    void setUp() throws IOException {
        User owner = userRepo.findByUsername(USERNAME).orElseGet(() ->
            userRepo.save(User.builder()
                .username(USERNAME).email("processing-service@example.com")
                .password("x").role(User.Role.ENGINEER).build()));

        Project project = projectRepo.save(Project.builder()
            .name("Processing").description("d")
            .phase(Project.ProjectPhase.DESIGN).build());

        Path source = storage.resolve("uuid_sheet.pdf");
        Files.writeString(source, "%PDF-1.7 original");

        document = documentRepo.save(Document.builder()
            .name("Sheet").fileName("sheet.pdf").fileType("application/pdf")
            .filePath(source.toString()).fileSize(Files.size(source))
            .documentType(Document.DocumentType.DRAWING)
            .project(project).uploadedBy(owner).build());
    }

    private ObjectNode answer(String json) {
        return (ObjectNode) mapper.readTree(json);
    }

    /** Makes the converter succeed, writing an output file as it would. */
    private void converterSucceedsWith(String endpoint, String json) {
        doAnswer(invocation -> {
            ObjectNode request = invocation.getArgument(1);
            String output = request.path("output").asString("");
            if (!output.isBlank()) {
                Files.writeString(Path.of(output), "%PDF-1.7 processed");
            }
            return answer(json);
        }).when(converter).callJson(eq(endpoint), any(), any(Duration.class));
    }

    private void converterAnswers(String endpoint, String json) {
        when(converter.callJson(eq(endpoint), any(), any(Duration.class)))
            .thenReturn(answer(json));
    }

    /**
     * A converter that writes part of its output and then reports failure.
     *
     * <p>This is what a real one does — it is a separate process writing to
     * a path this service handed it, and a conversion that dies partway has
     * already created the file. A stub that answers without writing makes
     * the cleanup assertions below vacuous: there is nothing to leak, so
     * removing the cleanup leaves them green. Verified by doing exactly
     * that.
     */
    private void converterWritesThenFails(String endpoint, String json) {
        doAnswer(invocation -> {
            writePartialOutput(invocation.getArgument(1));
            return answer(json);
        }).when(converter).callJson(eq(endpoint), any(), any(Duration.class));
    }

    private void converterWritesThenThrows(String endpoint) {
        doAnswer(invocation -> {
            writePartialOutput(invocation.getArgument(1));
            throw new IllegalStateException("connection reset");
        }).when(converter).callJson(eq(endpoint), any(), any(Duration.class));
    }

    private void writePartialOutput(ObjectNode request) throws IOException {
        String output = request.path("output").asString("");
        if (!output.isBlank()) {
            Files.writeString(Path.of(output), "%PDF-1.7 half written");
        }
    }

    /**
     * Every regular file under the document's storage, walked rather than
     * listed: work files go into a subdirectory that the first operation
     * creates and nothing removes, so a shallow listing counts the directory
     * itself as a leak and reports one on the very first run.
     */
    private List<Path> workFiles() throws IOException {
        try (var entries = Files.walk(storage)) {
            return entries.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private List<?> oneRegion() {
        return List.of(java.util.Map.of("page", 1, "x", 10, "y", 10, "w", 50, "h", 20));
    }

    // ── Describing a search ───────────────────────────────────────────────

    @Nested
    @DisplayName("naming a search in the version history")
    class Describing {

        @Test
        @DisplayName("names the terms searched for")
        void namesTerms() {
            var search = new TextSearch(List.of("invoice", "tender"), null, null, false, false);

            assertThat(search.describe()).isEqualTo("invoice, tender");
        }

        @Test
        @DisplayName("names the presets searched for")
        void namesPresets() {
            var search = new TextSearch(null, List.of("email", "phone"), null, false, false);

            assertThat(search.describe()).isEqualTo("email, phone");
        }

        @Test
        @DisplayName("counts a single expression rather than printing it")
        void countsOneExpression() {
            // A regular expression in a version summary is unreadable, and
            // it can carry the very pattern that was being hidden.
            var search = new TextSearch(null, null, List.of("\\d{3}-\\d{4}"), false, false);

            assertThat(search.describe()).isEqualTo("1 expression");
        }

        @Test
        @DisplayName("pluralises several expressions")
        void countsSeveralExpressions() {
            var search = new TextSearch(null, null, List.of("a", "b", "c"), false, false);

            assertThat(search.describe()).isEqualTo("3 expressions");
        }

        @Test
        @DisplayName("joins the kinds that were used")
        void joinsKinds() {
            var search = new TextSearch(
                List.of("invoice"), List.of("email"), List.of("a"), false, false);

            assertThat(search.describe()).isEqualTo("invoice and email and 1 expression");
        }

        @Test
        @DisplayName("says something rather than nothing for an empty search")
        void emptySearchStillReads() {
            // This reaches a version summary. "Redacted 4 match(es) of
            // across 2 page(s)" is what the alternative reads like.
            assertThat(new TextSearch(null, null, null, false, false).describe())
                .isEqualTo("the search");
        }

        @Test
        @DisplayName("treats empty lists the same as absent ones")
        void emptyListsAreAbsent() {
            var search = new TextSearch(List.of(), List.of(), List.of(), false, false);

            assertThat(search.describe()).isEqualTo("the search");
        }
    }

    // ── Redacting ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("redacting chosen regions")
    class Redacting {

        @Test
        @DisplayName("refuses a redaction with nothing selected")
        void refusesEmptySelection() {
            assertThatThrownBy(() -> processing.redact(document.getId(), List.of(), USERNAME))
                .isInstanceOf(DocumentProcessingException.class)
                .hasMessageContaining("at least one region");
        }

        @Test
        @DisplayName("refuses a redaction with no selection at all")
        void refusesNullSelection() {
            assertThatThrownBy(() -> processing.redact(document.getId(), null, USERNAME))
                .isInstanceOf(DocumentProcessingException.class);
        }

        @Test
        @DisplayName("commits a version when the converter reports success")
        void commitsAVersion() {
            converterSucceedsWith("/redact", """
                {"success":true,"redactedPages":2,"totalRegions":1}""");

            var result = processing.redact(document.getId(), oneRegion(), USERNAME);

            assertThat(result.version()).isNotNull();
            assertThat(result.details()).containsEntry("redactedPages", 2);
        }

        @Test
        @DisplayName("records who redacted it")
        void recordsTheActor() {
            converterSucceedsWith("/redact", """
                {"success":true,"redactedPages":1,"totalRegions":1}""");

            var result = processing.redact(document.getId(), oneRegion(), USERNAME);

            assertThat(result.version().getCreatedBy()).isNotNull();
        }

        @Test
        @DisplayName("records a system run honestly rather than inventing an actor")
        void recordsASystemRun() {
            converterSucceedsWith("/redact", """
                {"success":true,"redactedPages":1,"totalRegions":1}""");

            var result = processing.redact(document.getId(), oneRegion(), null);

            assertThat(result.version().getCreatedBy()).isNull();
        }

        @Test
        @DisplayName("commits nothing when the converter reports failure")
        void commitsNothingOnFailure() {
            converterAnswers("/redact", """
                {"success":false,"error":"This document is encrypted."}""");

            assertThatThrownBy(() -> processing.redact(document.getId(), oneRegion(), USERNAME))
                .isInstanceOf(DocumentProcessingException.class)
                .hasMessageContaining("encrypted");
        }

        @Test
        @DisplayName("explains a failure the converter did not explain")
        void explainsAnUnexplainedFailure() {
            converterAnswers("/redact", """
                {"success":false}""");

            assertThatThrownBy(() -> processing.redact(document.getId(), oneRegion(), USERNAME))
                .hasMessageContaining("could not process");
        }
    }

    // ── Redacting what a search found ─────────────────────────────────────

    @Nested
    @DisplayName("redacting every match of a search")
    class RedactingMatches {

        private final TextSearch search =
            new TextSearch(List.of("confidential"), null, null, false, false);

        @Test
        @DisplayName("searches again rather than trusting the client's preview")
        void searchesBeforeRedacting() {
            // Between previewing and applying, somebody else may have
            // committed a version that moves the text — stale coordinates
            // would black out the wrong part of the page and leave the
            // sensitive content readable.
            converterAnswers("/find-text", """
                {"success":true,"matches":[{"page":1,"x":1,"y":1,"w":2,"h":2}]}""");
            converterSucceedsWith("/redact", """
                {"success":true,"redactedPages":1}""");

            var result = processing.redactMatching(document.getId(), search, USERNAME);

            assertThat(result.version()).isNotNull();
        }

        @Test
        @DisplayName("refuses when the search itself failed")
        void refusesWhenSearchFailed() {
            converterAnswers("/find-text", """
                {"success":false,"error":"The text layer is damaged."}""");

            assertThatThrownBy(() -> processing.redactMatching(document.getId(), search, USERNAME))
                .hasMessageContaining("text layer is damaged");
        }

        @Test
        @DisplayName("refuses when there is nothing to redact")
        void refusesWhenNothingMatched() {
            converterAnswers("/find-text", """
                {"success":true,"matches":[]}""");

            assertThatThrownBy(() -> processing.redactMatching(document.getId(), search, USERNAME))
                .hasMessageContaining("nothing to redact");
        }

        @Test
        @DisplayName("says to run OCR when the pages had no text to search")
        void suggestsOcrForScannedPages() {
            // The difference between "this document does not contain that"
            // and "this document could not be searched" — and only the
            // second one has an action attached.
            converterAnswers("/find-text", """
                {"success":true,"matches":[],"pagesWithoutText":4}""");

            assertThatThrownBy(() -> processing.redactMatching(document.getId(), search, USERNAME))
                .hasMessageContaining("run OCR first");
        }

        @Test
        @DisplayName("names the search in the version summary")
        void namesTheSearchInTheSummary() {
            converterAnswers("/find-text", """
                {"success":true,"matches":[{"page":1,"x":1,"y":1,"w":2,"h":2}]}""");
            converterSucceedsWith("/redact", """
                {"success":true,"redactedPages":1}""");

            var result = processing.redactMatching(document.getId(), search, USERNAME);

            assertThat(result.version().getSummary()).contains("confidential");
        }
    }

    // ── The shared pipeline ───────────────────────────────────────────────

    @Nested
    @DisplayName("the pipeline every operation shares")
    class Pipeline {

        @Test
        @DisplayName("refuses a document that does not exist")
        void refusesUnknownDocument() {
            assertThatThrownBy(() -> processing.redact(9_999_999L, oneRegion(), USERNAME))
                .isInstanceOf(DocumentProcessingException.class)
                .hasMessageContaining("not found");
        }

        @Test
        @DisplayName("refuses a document with no stored file")
        void refusesDocumentWithNoFile() {
            Document detached = documentRepo.save(Document.builder()
                .name("No file").fileName("none.pdf").fileType("application/pdf")
                .filePath(null)
                .documentType(Document.DocumentType.DRAWING)
                .project(document.getProject()).uploadedBy(document.getUploadedBy()).build());

            assertThatThrownBy(() -> processing.redact(detached.getId(), oneRegion(), USERNAME))
                .hasMessageContaining("no stored file");
        }

        @Test
        @DisplayName("leaves no work file behind when the converter refuses")
        void cleansUpAfterARefusal() throws IOException {
            // One orphaned work file per failed operation fills a disk
            // slowly enough that nobody connects it to redaction.
            converterWritesThenFails("/redact", """
                {"success":false,"error":"nope"}""");
            List<Path> before = workFiles();

            assertThatThrownBy(() -> processing.redact(document.getId(), oneRegion(), USERNAME));

            assertThat(workFiles()).containsExactlyInAnyOrderElementsOf(before);
        }

        @Test
        @DisplayName("leaves no work file behind when the converter throws")
        void cleansUpAfterAnException() throws IOException {
            converterWritesThenThrows("/redact");
            List<Path> before = workFiles();

            assertThatThrownBy(() -> processing.redact(document.getId(), oneRegion(), USERNAME));

            assertThat(workFiles()).containsExactlyInAnyOrderElementsOf(before);
        }
    }

    // ── Reading without changing ──────────────────────────────────────────

    @Nested
    @DisplayName("looking at a document without rewriting it")
    class Inspecting {

        @Test
        @DisplayName("passes the search through to the converter")
        void findsText() {
            converterAnswers("/find-text", """
                {"success":true,"matchCount":3}""");

            JsonNode found = processing.findText(document.getId(),
                new TextSearch(List.of("fire door"), null, null, true, true));

            assertThat(found.path("matchCount").asInt()).isEqualTo(3);
        }

        @Test
        @DisplayName("refuses to search a document with no stored file")
        void refusesToSearchWithoutAFile() {
            Document detached = documentRepo.save(Document.builder()
                .name("No file").fileName("none.pdf").fileType("application/pdf")
                .filePath("  ")
                .documentType(Document.DocumentType.DRAWING)
                .project(document.getProject()).uploadedBy(document.getUploadedBy()).build());

            assertThatThrownBy(() -> processing.findText(detached.getId(),
                new TextSearch(List.of("x"), null, null, false, false)))
                .hasMessageContaining("no stored file");
        }
    }
}
