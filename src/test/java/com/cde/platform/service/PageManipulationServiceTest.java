package com.cde.platform.service;

import com.cde.platform.exception.DocumentProcessingException;
import com.cde.platform.service.PageArrangement.PageRef;
import com.cde.platform.model.Document;
import com.cde.platform.model.Project;
import com.cde.platform.model.User;
import com.cde.platform.repository.DocumentRepository;
import com.cde.platform.repository.ProjectRepository;
import com.cde.platform.repository.UserRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/**
 * Rearranging, inserting and extracting pages.
 *
 * <p>Three operations over one converter call, and most of what is worth
 * holding is what they refuse. A page organiser sends a whole intended
 * layout rather than a sequence of drags, so an empty layout is a request
 * to delete every page in the document — and that has to be refused at this
 * level rather than left to the converter, because by the time the converter
 * has an opinion the work file already exists.
 *
 * <p>The converter is stubbed, and stubbed so that it writes its output
 * before failing where a failure is being tested. A stub that answers
 * without writing makes the work-file assertions vacuous: there is nothing
 * to leak, so they pass with the cleanup removed.
 */
@SpringBootTest
@DisplayName("rearranging a document’s pages")
class PageManipulationServiceTest {

    private static final String USERNAME = "page-manipulation-user";

    @Autowired PageManipulationService pages;
    @Autowired DocumentRepository documentRepo;
    @Autowired ProjectRepository projectRepo;
    @Autowired UserRepository userRepo;

    @MockitoBean ConverterService converter;

    @TempDir Path storage;

    private final ObjectMapper mapper = new ObjectMapper();
    private Document sheet;
    private Document donor;

    @BeforeEach
    void setUp() throws IOException {
        User owner = userRepo.findByUsername(USERNAME).orElseGet(() ->
            userRepo.save(User.builder()
                .username(USERNAME).email("page-manipulation@example.com")
                .password("x").role(User.Role.ENGINEER).build()));

        Project project = projectRepo.save(Project.builder()
            .name("Pages").description("d")
            .phase(Project.ProjectPhase.DESIGN).build());

        sheet = store(project, owner, "sheet.pdf");
        donor = store(project, owner, "appendix.pdf");

        pageInfoReports(6);
        rearrangeSucceeds();
    }

    private Document store(Project project, User owner, String fileName) throws IOException {
        Path path = storage.resolve("uuid_" + fileName);
        Files.writeString(path, "%PDF-1.7 " + fileName);
        return documentRepo.save(Document.builder()
            .name(fileName.replace(".pdf", "")).fileName(fileName)
            .fileType("application/pdf")
            .filePath(path.toString()).fileSize(Files.size(path))
            .documentType(Document.DocumentType.DRAWING)
            .project(project).uploadedBy(owner).build());
    }

    private ObjectNode answer(String json) {
        return (ObjectNode) mapper.readTree(json);
    }

    private void pageInfoReports(int pageCount) {
        doAnswer(invocation -> answer("{\"success\":true,\"pageCount\":" + pageCount + "}"))
            .when(converter).callJson(eq("/page-info"), any(), any(Duration.class));
    }

    private void rearrangeSucceeds() {
        doAnswer(invocation -> {
            writeOutput(invocation.getArgument(1));
            return answer("{\"success\":true}");
        }).when(converter).callJson(eq("/rearrange-pages"), any(), any(Duration.class));
    }

    /** Writes the output, then reports failure — what a real converter does. */
    private void rearrangeWritesThenFails(String error) {
        doAnswer(invocation -> {
            writeOutput(invocation.getArgument(1));
            return answer("{\"success\":false,\"error\":\"" + error + "\"}");
        }).when(converter).callJson(eq("/rearrange-pages"), any(), any(Duration.class));
    }

    private void writeOutput(ObjectNode request) throws IOException {
        String output = request.path("output").asString("");
        if (!output.isBlank()) {
            Files.writeString(Path.of(output), "%PDF-1.7 rearranged");
        }
    }

    private List<Path> filesOnDisk() throws IOException {
        try (var entries = Files.walk(storage)) {
            return entries.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private PageArrangement layoutOf(int... pageNumbers) {
        return new PageArrangement(
            java.util.Arrays.stream(pageNumbers).boxed().map(PageRef::of).toList());
    }

    // ── Reading the layout ────────────────────────────────────────────────

    @Nested
    @DisplayName("describing the pages")
    class Describing {

        @Test
        @DisplayName("passes the converter's answer through")
        void reportsPageCount() {
            JsonNode info = pages.describePages(sheet.getId());

            assertThat(info.path("pageCount").asInt()).isEqualTo(6);
        }

        @Test
        @DisplayName("refuses a document that does not exist")
        void refusesUnknownDocument() {
            assertThatThrownBy(() -> pages.describePages(9_999_999L))
                .isInstanceOf(DocumentProcessingException.class)
                .hasMessageContaining("not found");
        }
    }

    // ── Rearranging ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("applying a layout")
    class Arranging {

        @Test
        @DisplayName("commits the layout as one version")
        void commitsOneVersion() {
            // The organiser sends the whole intended layout rather than a
            // command per drag, so a batch of edits lands as one version
            // described by its net effect.
            var result = pages.arrange(sheet.getId(), layoutOf(3, 1, 2), USERNAME);

            assertThat(result.version()).isNotNull();
            assertThat(result.pageCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("refuses a layout with no pages in it")
        void refusesAnEmptyLayout() {
            // An empty layout is a request to delete every page. Refused
            // here rather than left to the converter, because by the time
            // the converter has an opinion the work file exists.
            assertThatThrownBy(() -> pages.arrange(sheet.getId(), layoutOf(), USERNAME))
                .hasMessageContaining("at least one page");
        }

        @Test
        @DisplayName("refuses a layout that is absent altogether")
        void refusesANullLayout() {
            assertThatThrownBy(() -> pages.arrange(sheet.getId(), null, USERNAME))
                .hasMessageContaining("at least one page");
        }

        @Test
        @DisplayName("passes the converter's complaint on, since it names the page")
        void reportsTheConvertersReason() {
            rearrangeWritesThenFails("Page 9 is not in this document");

            assertThatThrownBy(() -> pages.arrange(sheet.getId(), layoutOf(9), USERNAME))
                .hasMessageContaining("Page 9");
        }

        @Test
        @DisplayName("leaves no work file behind when the converter refuses")
        void cleansUpAfterARefusal() throws IOException {
            rearrangeWritesThenFails("nope");
            List<Path> before = filesOnDisk();

            assertThatThrownBy(() -> pages.arrange(sheet.getId(), layoutOf(1, 2), USERNAME));

            assertThat(filesOnDisk()).containsExactlyInAnyOrderElementsOf(before);
        }
    }

    // ── Inserting from another document ───────────────────────────────────

    @Nested
    @DisplayName("inserting pages from another document")
    class Inserting {

        @Test
        @DisplayName("places the inserted block where it was asked for")
        void insertsAtThePositionGiven() {
            var result = pages.insertPages(
                sheet.getId(), donor.getId(), List.of(1, 2), 3, USERNAME);

            // Six pages were there, two arrive: 2 before, 2 inserted, 4 after.
            assertThat(result.pageCount()).isEqualTo(8);
        }

        @Test
        @DisplayName("appends when the position is past the end")
        void appendsWhenPositionIsPastTheEnd() {
            // Clamped rather than refused: "insert at page 40" of a
            // six-page document plainly means at the end, and refusing it
            // teaches nobody anything.
            var result = pages.insertPages(
                sheet.getId(), donor.getId(), List.of(1), 40, USERNAME);

            assertThat(result.pageCount()).isEqualTo(7);
        }

        @Test
        @DisplayName("clamps a position before the first page")
        void clampsAPositionBeforeTheStart() {
            var result = pages.insertPages(
                sheet.getId(), donor.getId(), List.of(1), 0, USERNAME);

            assertThat(result.pageCount()).isEqualTo(7);
        }

        @Test
        @DisplayName("names the donor document in the version summary")
        void namesTheDonor() {
            var result = pages.insertPages(
                sheet.getId(), donor.getId(), List.of(1, 2), 1, USERNAME);

            assertThat(result.version().getSummary()).contains("appendix");
        }

        @Test
        @DisplayName("refuses to insert a document into itself")
        void refusesSelfInsert() {
            // Self-insert is duplication, which applying a layout already
            // does without opening the file twice.
            assertThatThrownBy(() -> pages.insertPages(
                sheet.getId(), sheet.getId(), List.of(1), 1, USERNAME))
                .hasMessageContaining("duplicate them instead");
        }

        @Test
        @DisplayName("refuses an insert with no pages chosen")
        void refusesNoPages() {
            assertThatThrownBy(() -> pages.insertPages(
                sheet.getId(), donor.getId(), List.of(), 1, USERNAME))
                .hasMessageContaining("at least one page");
        }

        @Test
        @DisplayName("refuses an insert from a document with no stored file")
        void refusesADonorWithNoFile() {
            Document detached = documentRepo.save(Document.builder()
                .name("No file").fileName("none.pdf").fileType("application/pdf")
                .filePath(null).documentType(Document.DocumentType.DRAWING)
                .project(sheet.getProject()).uploadedBy(sheet.getUploadedBy()).build());

            assertThatThrownBy(() -> pages.insertPages(
                sheet.getId(), detached.getId(), List.of(1), 1, USERNAME))
                .hasMessageContaining("no stored file");
        }
    }

    // ── Extracting to a new document ──────────────────────────────────────

    @Nested
    @DisplayName("extracting pages into a new document")
    class Extracting {

        @Test
        @DisplayName("creates a document holding the chosen pages")
        void createsANewDocument() {
            var result = pages.extractPages(sheet.getId(), List.of(2, 3), null, USERNAME);

            assertThat(result.document().getId()).isNotEqualTo(sheet.getId());
            assertThat(result.pageCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("leaves the document it came from untouched")
        void leavesTheSourceAlone() {
            String pathBefore = sheet.getFilePath();

            pages.extractPages(sheet.getId(), List.of(2), null, USERNAME);

            assertThat(documentRepo.findById(sheet.getId()).orElseThrow().getFilePath())
                .isEqualTo(pathBefore);
        }

        @Test
        @DisplayName("uses the name it was given")
        void usesTheGivenName() {
            var result = pages.extractPages(
                sheet.getId(), List.of(1), "Fire strategy", USERNAME);

            assertThat(result.document().getName()).isEqualTo("Fire strategy");
        }

        @Test
        @DisplayName("names a run of pages as a range, the way people write them")
        void namesARunAsARange() {
            var result = pages.extractPages(sheet.getId(), List.of(2, 3, 4), null, USERNAME);

            assertThat(result.document().getName()).contains("2-4");
        }

        @Test
        @DisplayName("lists scattered pages rather than pretending they are a range")
        void listsScatteredPages() {
            // "1-7" for pages 1, 4 and 7 would describe a seven-page
            // document that does not exist.
            var result = pages.extractPages(sheet.getId(), List.of(1, 4, 7), null, USERNAME);

            assertThat(result.document().getName()).contains("1, 4, 7");
        }

        @Test
        @DisplayName("sorts a selection that arrived out of order")
        void sortsTheSelection() {
            var result = pages.extractPages(sheet.getId(), List.of(4, 2, 3), null, USERNAME);

            assertThat(result.document().getName()).contains("2-4");
        }

        @Test
        @DisplayName("falls back to a derived name when the one given is blank")
        void ignoresABlankName() {
            var result = pages.extractPages(sheet.getId(), List.of(1), "   ", USERNAME);

            assertThat(result.document().getName()).contains("sheet");
        }

        @Test
        @DisplayName("says where the pages came from")
        void recordsTheSource() {
            var result = pages.extractPages(sheet.getId(), List.of(1, 2), null, USERNAME);

            assertThat(result.document().getDescription()).contains("sheet").contains("1-2");
        }

        @Test
        @DisplayName("starts the new document as a draft")
        void startsAsADraft() {
            // It is a new artefact nobody has reviewed, whatever state the
            // document it came from was in.
            var result = pages.extractPages(sheet.getId(), List.of(1), null, USERNAME);

            assertThat(result.document().getStatus()).isEqualTo(Document.DocumentStatus.DRAFT);
        }

        @Test
        @DisplayName("refuses an extraction with no pages chosen")
        void refusesNoPages() {
            assertThatThrownBy(() ->
                pages.extractPages(sheet.getId(), List.of(), null, USERNAME))
                .hasMessageContaining("at least one page");
        }

        @Test
        @DisplayName("creates no document when the extraction failed")
        void createsNothingOnFailure() {
            rearrangeWritesThenFails("Page 9 is not in this document");
            long before = documentRepo.count();

            assertThatThrownBy(() ->
                pages.extractPages(sheet.getId(), List.of(9), null, USERNAME));

            assertThat(documentRepo.count()).isEqualTo(before);
        }
    }

    // ── The edges each guard has two of ───────────────────────────────────

    @Nested
    @DisplayName("edges of the page selection")
    class SelectionEdges {

        @Test
        @DisplayName("an insert with no selection at all is refused, not just an empty one")
        void nullInsertSelectionIsRefused() {
            // A client that omitted the property rather than sending it empty.
            // Both are "nothing chosen" and both have to be refused before the
            // files are opened, because the alternative is two PDFs read into
            // memory to discover there was nothing to do.
            assertThatThrownBy(() -> pages.insertPages(
                    sheet.getId(), donor.getId(), null, 2, USERNAME))
                .isInstanceOf(DocumentProcessingException.class)
                .hasMessageContaining("at least one page");
        }

        @Test
        @DisplayName("an extraction with no selection at all is refused")
        void nullExtractionSelectionIsRefused() {
            assertThatThrownBy(() -> pages.extractPages(
                    sheet.getId(), null, "Extract", USERNAME))
                .isInstanceOf(DocumentProcessingException.class)
                .hasMessageContaining("at least one page");
        }

        @Test
        @DisplayName("a single page is listed as itself, not as a one-page range")
        void aSinglePageIsNotARange() {
            // "4-4" is how a loop writes it and not how anyone reads it.
            Document created = pages.extractPages(sheet.getId(), List.of(4), null, USERNAME)
                .document();

            assertThat(created.getName()).contains("4").doesNotContain("4-4");
        }

        @Test
        @DisplayName("a selection with a repeated page does not become a false range")
        void duplicatesDoNotFakeContiguity() {
            // 2, 2, 3 is three entries spanning two numbers. Counting entries
            // rather than distinct values would read it as the run 2-4.
            Document created = pages.extractPages(
                sheet.getId(), List.of(2, 2, 3), null, USERNAME).document();

            assertThat(created.getName()).contains("2-3").doesNotContain("2-4");
        }

        @Test
        @DisplayName("extraction with no actor named is attributed to the source's author")
        void extractionWithoutAnActorInheritsAttribution() {
            // Scheduled and system-initiated work has no signed-in user, and the
            // new document still needs an author: pages taken out of someone's
            // drawing are their drawing. Leaving it empty would produce a
            // document with no owner in a column the audit trail reads.
            Document created = pages.extractPages(sheet.getId(), List.of(1, 2), null, null)
                .document();

            assertThat(created.getUploadedBy()).isNotNull();
            assertThat(created.getUploadedBy().getId())
                .isEqualTo(sheet.getUploadedBy().getId());
        }

        @Test
        @DisplayName("a username nobody has falls back the same way rather than failing")
        void extractionWithAnUnknownActorInheritsAttribution() {
            // A user deleted between the request being made and the job running.
            // Failing here would lose work that is otherwise complete.
            Document created = pages.extractPages(
                sheet.getId(), List.of(1, 2), null, "nobody-by-this-name").document();

            assertThat(created.getUploadedBy()).isNotNull();
            assertThat(created.getUploadedBy().getId())
                .isEqualTo(sheet.getUploadedBy().getId());
        }

        @Test
        @DisplayName("extraction from a document filed under no project still lands somewhere")
        void extractionFromAProjectlessDocument() throws IOException {
            // The new document's directory is derived from the project. Without
            // one it goes beside the file it came from, which is the only
            // location that is certainly writable.
            Document loose = documentRepo.save(Document.builder()
                .name("loose").fileName("loose.pdf").fileType("application/pdf")
                .filePath(sheet.getFilePath()).fileSize(1L)
                .documentType(Document.DocumentType.DRAWING)
                .project(null).uploadedBy(null).build());

            Document created = pages.extractPages(loose.getId(), List.of(1), null, USERNAME)
                .document();

            assertThat(Path.of(created.getFilePath())).exists();
        }

        @Test
        @DisplayName("a document with an empty file path is refused like one with none")
        void blankFilePathIsRefused() {
            // An empty string is not a path, and Paths.get("") resolves to the
            // working directory — which would have the converter open whatever
            // happens to be there.
            Document pathless = documentRepo.save(Document.builder()
                .name("pathless").fileName("pathless.pdf").fileType("application/pdf")
                .filePath("   ").fileSize(1L)
                .documentType(Document.DocumentType.DRAWING)
                .project(null).uploadedBy(null).build());

            assertThatThrownBy(() -> pages.extractPages(
                    pathless.getId(), List.of(1), null, USERNAME))
                .isInstanceOf(DocumentProcessingException.class)
                .hasMessageContaining("no stored file");
        }

        @Test
        @DisplayName("a page count the converter could not read is reported, not assumed")
        void unreadablePageCountIsReported() {
            // Defaulting to zero would make every position "past the end" and
            // quietly append, which looks like the layout was applied.
            doAnswer(invocation -> answer(
                    "{\"success\":false,\"error\":\"the file is not a readable PDF\"}"))
                .when(converter).callJson(eq("/page-info"), any(), any(Duration.class));

            assertThatThrownBy(() -> pages.insertPages(
                    sheet.getId(), donor.getId(), List.of(1), 2, USERNAME))
                .isInstanceOf(DocumentProcessingException.class)
                .hasMessageContaining("not a readable PDF");
        }

        @Test
        @DisplayName("a page-info failure with no reason given still says something useful")
        void unreadablePageCountWithoutAReason() {
            doAnswer(invocation -> answer("{\"success\":false}"))
                .when(converter).callJson(eq("/page-info"), any(), any(Duration.class));

            assertThatThrownBy(() -> pages.insertPages(
                    sheet.getId(), donor.getId(), List.of(1), 2, USERNAME))
                .isInstanceOf(DocumentProcessingException.class)
                .hasMessageContaining("could not be read");
        }
    }
}
