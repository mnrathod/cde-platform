package com.cde.platform.controller;

import com.cde.platform.model.Document;
import com.cde.platform.model.Project;
import com.cde.platform.model.User;
import com.cde.platform.repository.DocumentRepository;
import com.cde.platform.repository.ProjectRepository;
import com.cde.platform.repository.UserRepository;
import com.cde.platform.support.ActingAs;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Comparing two revisions of a drawing.
 *
 * <p>The comparison itself belongs to the conversion service, which knows how
 * to read each format. What this endpoint owns is everything around it, and
 * that is what is worth testing: resolving both documents, refusing one the
 * caller cannot see without confirming it exists elsewhere, refusing one with
 * no file behind it, and — the part a client depends on most — telling apart
 * "these two could not be compared" from "the service that compares them is
 * not running". Those need different things from the reader, so answering both
 * the same way would be a defect even though the request failed either way.
 *
 * <p>A stub {@code HttpServer} on a loopback port stands in for the converter,
 * pointed at by {@code cde.converter.url}. That keeps the real request, the
 * real client and the real parsing in the test; only the far end is invented.
 * Started in a static initialiser rather than {@code @BeforeAll} because
 * {@code @DynamicPropertySource} is read when the context loads, and each
 * {@code @Nested} class loads its own — before the outer class's
 * {@code @BeforeAll} would have run.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActingAs(value = User.Role.ENGINEER, username = CompareControllerTest.USERNAME)
@DisplayName("comparing two documents")
class CompareControllerTest {

    static final String USERNAME = "compare-user";

    /** What the stub converter answers next, and whether it answers at all. */
    private record Reply(int status, String body, boolean hangUp) {
        static Reply json(String body) { return new Reply(200, body, false); }
        static Reply refusing() { return new Reply(500, "{}", false); }
        static Reply unreachable() { return new Reply(0, "", true); }
    }

    private static final AtomicReference<Reply> nextReply =
        new AtomicReference<>(Reply.json("{}"));

    /** The body the converter was asked to compare, for asserting on. */
    private static final AtomicReference<String> lastRequestBody = new AtomicReference<>("");

    private static HttpServer converter;

    static {
        try {
            converter = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            converter.createContext("/", exchange -> {
                lastRequestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                Reply reply = nextReply.get();
                if (reply.hangUp()) {
                    // Closing without answering is not the same as refusing to
                    // connect, so the unreachable case is served by stopping
                    // the listener instead — see comparingWhileTheServiceIsDown.
                    exchange.close();
                    return;
                }
                byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(reply.status(), body.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            converter.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> converter.stop(0)));
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void pointAtStubConverter(DynamicPropertyRegistry registry) {
        registry.add("cde.converter.url",
            () -> "http://127.0.0.1:" + converter.getAddress().getPort());
    }

    @Autowired MockMvc mockMvc;
    @Autowired DocumentRepository documentRepo;
    @Autowired ProjectRepository projectRepo;
    @Autowired UserRepository userRepo;

    @TempDir Path storage;

    private Project project;
    private User owner;

    @BeforeEach
    void setUp() {
        nextReply.set(Reply.json("{}"));
        lastRequestBody.set("");
        owner = userRepo.findByUsername(USERNAME).orElseGet(() ->
            userRepo.save(User.builder()
                .username(USERNAME).email("compare@example.test")
                .password("{noop}irrelevant").role(User.Role.ENGINEER).build()));
        project = projectRepo.save(Project.builder()
            .name("Comparisons " + System.nanoTime())
            .phase(Project.ProjectPhase.DESIGN).build());
    }

    private Document drawing(String name, String revision) throws IOException {
        Path path = storage.resolve(UUID.randomUUID() + "_" + name + ".pdf");
        Files.writeString(path, "%PDF-1.7 " + revision);
        return documentRepo.save(Document.builder()
            .name(name).fileName(name + ".pdf").fileType(MediaType.APPLICATION_PDF_VALUE)
            .filePath(path.toString()).fileSize(Files.size(path))
            .revision(revision)
            .documentType(Document.DocumentType.DRAWING)
            .project(project).uploadedBy(owner).build());
    }

    /** A row whose file is recorded but is not on disk. */
    private Document withMissingFile() {
        return documentRepo.save(Document.builder()
            .name("Detached").fileName("gone.pdf").fileType(MediaType.APPLICATION_PDF_VALUE)
            .filePath(storage.resolve("never-written.pdf").toString())
            .documentType(Document.DocumentType.DRAWING)
            .project(project).uploadedBy(owner).build());
    }

    private Document withNoFileAtAll() {
        return documentRepo.save(Document.builder()
            .name("Placeholder").fileName("none.pdf").filePath(null)
            .documentType(Document.DocumentType.DRAWING)
            .project(project).uploadedBy(owner).build());
    }

    private org.springframework.test.web.servlet.ResultActions compare(Long first, Long second)
            throws Exception {
        return mockMvc.perform(post("/api/compare")
            .with(org.springframework.security.test.web.servlet.request
                .SecurityMockMvcRequestPostProcessors.csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"documentId1\":" + first + ",\"documentId2\":" + second + "}"));
    }

    @Nested
    @DisplayName("when both documents are there")
    class BothPresent {

        @Test
        @DisplayName("answers with the comparison")
        void answersWithTheComparison() throws Exception {
            nextReply.set(Reply.json("{\"success\":true,\"addedPages\":2}"));
            Document first = drawing("GA Plan", "P01");
            Document second = drawing("GA Plan", "P02");

            compare(first.getId(), second.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("says which revision was which, so the result can be read")
        void namesBothRevisions() throws Exception {
            // Without this the reply is a diff with no way to tell which side
            // is the earlier drawing — the one thing a reviewer needs from it.
            Document first = drawing("GA Plan", "P01");
            Document second = drawing("GA Plan", "P02");

            compare(first.getId(), second.getId())
                .andExpect(jsonPath("$.doc1Revision").value("P01"))
                .andExpect(jsonPath("$.doc2Revision").value("P02"));
        }

        @Test
        @DisplayName("names both documents and their files")
        void namesBothDocuments() throws Exception {
            Document first = drawing("Level 02", "P01");
            Document second = drawing("Level 03", "P01");

            compare(first.getId(), second.getId())
                .andExpect(jsonPath("$.doc1Name").value("Level 02"))
                .andExpect(jsonPath("$.doc2Name").value("Level 03"))
                .andExpect(jsonPath("$.doc1FileName").value("Level 02.pdf"));
        }

        @Test
        @DisplayName("sends both files and their types to the service that reads them")
        void sendsBothPathsToTheConverter() throws Exception {
            Document first = drawing("Plan", "P01");
            Document second = drawing("Plan", "P02");

            compare(first.getId(), second.getId()).andExpect(status().isOk());

            assertThat(lastRequestBody.get())
                .contains(first.getFilePath())
                .contains(second.getFilePath())
                .contains(MediaType.APPLICATION_PDF_VALUE);
        }

        @Test
        @DisplayName("carries the comparison's own members through, whatever they are")
        void carriesTheConvertersOwnMembers() throws Exception {
            // What differs varies with what was compared, which is why these
            // travel under `comparison` rather than a shape that would be
            // mostly absent for any given pair.
            nextReply.set(Reply.json(
                "{\"success\":true,\"changedRegions\":7,\"similarity\":0.94}"));
            Document first = drawing("Plan", "P01");
            Document second = drawing("Plan", "P02");

            compare(first.getId(), second.getId())
                .andExpect(jsonPath("$.comparison.changedRegions").value(7))
                .andExpect(jsonPath("$.comparison.similarity").value(0.94));
        }

        @Test
        @DisplayName("does not repeat success and error inside the comparison")
        void doesNotRepeatTheLiftedMembers() throws Exception {
            // They are lifted into named fields; leaving copies behind would
            // let the two disagree after a later change.
            nextReply.set(Reply.json("{\"success\":true,\"pages\":3}"));
            Document first = drawing("Plan", "P01");
            Document second = drawing("Plan", "P02");

            compare(first.getId(), second.getId())
                .andExpect(jsonPath("$.comparison.success").doesNotExist())
                .andExpect(jsonPath("$.comparison.error").doesNotExist())
                .andExpect(jsonPath("$.comparison.pages").value(3));
        }

        @Test
        @DisplayName("reports a comparison the service itself could not make")
        void reportsAFailedComparison() throws Exception {
            // A reachable service saying no. The request was fine and the
            // answer is a comparison that did not happen, with the reason.
            nextReply.set(Reply.json(
                "{\"success\":false,\"error\":\"Page counts differ too widely\"}"));
            Document first = drawing("Plan", "P01");
            Document second = drawing("Other", "P01");

            compare(first.getId(), second.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("Page counts differ too widely"));
        }

        @Test
        @DisplayName("treats a comparison that says nothing about success as successful")
        void defaultsSuccessWhenUnstated() throws Exception {
            // Older converter builds answer without the flag. Reading its
            // absence as failure would report every one of them as broken.
            nextReply.set(Reply.json("{\"addedPages\":1}"));
            Document first = drawing("Plan", "P01");
            Document second = drawing("Plan", "P02");

            compare(first.getId(), second.getId())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.error").doesNotExist());
        }

        @Test
        @DisplayName("comparing a document with itself is allowed")
        void allowsComparingADocumentWithItself() throws Exception {
            // Not an error: it is how somebody checks that a re-export of the
            // same drawing really is identical.
            Document only = drawing("Plan", "P01");

            compare(only.getId(), only.getId()).andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("when a document cannot be compared")
    class Refusals {

        @Test
        @DisplayName("a document that does not exist is a 404")
        void absentDocumentIsNotFound() throws Exception {
            Document present = drawing("Plan", "P01");

            compare(present.getId(), 987_654_321L).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("the 404 is the same whichever side is missing")
        void eitherSideMissingIsTheSame() throws Exception {
            // Answering differently would tell a caller which of two
            // identifiers exists, which is the thing the 404 exists to avoid.
            Document present = drawing("Plan", "P01");

            compare(987_654_321L, present.getId()).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("a document with no file recorded is a 422, not a 404")
        void noFileRecordedIsUnprocessable() throws Exception {
            // The document is real and the caller may see it. What is missing
            // is bytes to compare, and that is a different remedy.
            Document present = drawing("Plan", "P01");

            compare(present.getId(), withNoFileAtAll().getId())
                .andExpect(status().isUnprocessableContent());
        }

        @Test
        @DisplayName("a document whose file has gone from disk is a 422 as well")
        void missingFileOnDiskIsUnprocessable() throws Exception {
            Document present = drawing("Plan", "P01");

            compare(present.getId(), withMissingFile().getId())
                .andExpect(status().isUnprocessableContent());
        }

        @Test
        @DisplayName("the refusal says the file is missing rather than quoting a path")
        void refusalDoesNotExposeTheStoragePath() throws Exception {
            // §5.13.13: never expose bucket paths. The message has to be
            // actionable without being a map of the storage layout.
            Document present = drawing("Plan", "P01");
            Document detached = withMissingFile();

            String body = compare(present.getId(), detached.getId())
                .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain(detached.getFilePath());
            assertThat(body).contains("nothing to compare");
        }

        @Test
        @DisplayName("a request naming no document at all is refused as invalid")
        void missingIdentifierIsRejected() throws Exception {
            mockMvc.perform(post("/api/compare")
                    .with(org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"documentId1\":1}"))
                .andExpect(status().is4xxClientError());
        }
    }

    @Nested
    @DisplayName("when the comparison service is having trouble")
    class ConverterTrouble {

        @Test
        @DisplayName("an answer that is not a comparison is reported as a failed comparison")
        void unreadableAnswerIsAFailedComparison() throws Exception {
            nextReply.set(new Reply(200, "<html>Bad Gateway</html>", false));
            Document first = drawing("Plan", "P01");
            Document second = drawing("Plan", "P02");

            compare(first.getId(), second.getId())
                .andExpect(status().isUnprocessableContent());
        }

        @Test
        @DisplayName("a service error is never reported as a successful comparison")
        void serviceErrorIsNotReportedAsSuccess() throws Exception {
            // The defect this found: only the body was being read, never the
            // status. A converter answering 500 with `{}` came back as a
            // successful comparison, because `success` defaults to true when
            // the member is absent — so a comparison that never happened was
            // presented as one that found no differences.
            nextReply.set(Reply.refusing());
            Document first = drawing("Plan", "P01");
            Document second = drawing("Plan", "P02");

            compare(first.getId(), second.getId())
                .andExpect(status().isUnprocessableContent());
        }

        @Test
        @DisplayName("a service error passes on the reason it gave")
        void serviceErrorCarriesItsOwnReason() throws Exception {
            nextReply.set(new Reply(500,
                "{\"error\":\"Page counts differ too widely\"}", false));
            Document first = drawing("Plan", "P01");
            Document second = drawing("Plan", "P02");

            String body = compare(first.getId(), second.getId())
                .andExpect(status().isUnprocessableContent())
                .andReturn().getResponse().getContentAsString();

            assertThat(body).contains("Page counts differ too widely");
        }

        @Test
        @DisplayName("a service error that is not JSON is not echoed at the reader")
        void serviceErrorDoesNotEchoAnErrorPage() throws Exception {
            // The body of a failed call is as likely to be an HTML error page
            // or a stack trace as a message, and §1.4 rules out both.
            nextReply.set(new Reply(500,
                "<html><body>Traceback (most recent call last)...</body></html>", false));
            Document first = drawing("Plan", "P01");
            Document second = drawing("Plan", "P02");

            String body = compare(first.getId(), second.getId())
                .andExpect(status().isUnprocessableContent())
                .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain("Traceback").doesNotContain("<html>");
        }

        @Test
        @DisplayName("a closed connection does not become a 500")
        void aClosedConnectionIsNotAServerFault() throws Exception {
            nextReply.set(Reply.unreachable());
            Document first = drawing("Plan", "P01");
            Document second = drawing("Plan", "P02");

            compare(first.getId(), second.getId())
                .andExpect(status().is4xxClientError());
        }
    }
}
