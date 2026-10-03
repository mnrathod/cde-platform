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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Opening a 3D model through the general viewer endpoint.
 *
 * <p>This is the branch of {@code /api/viewer/{id}} that handles model files,
 * and it answers in three different shapes depending on what it was given.
 * A mesh format comes back as bytes with a header naming the format, because
 * the browser's loader is chosen by that header. An IFC comes back as JSON,
 * because the converter extracts a structure rather than a mesh. A Revit file
 * comes back as neither — it says it has to be exported first, since no open
 * toolchain reads the format.
 *
 * <p>Telling those apart is the whole job, and getting it wrong is not a
 * subtle failure: a viewer handed JSON where it expected a mesh shows an empty
 * canvas, and one handed bytes where it expected JSON shows nothing at all.
 * Neither says why.
 *
 * <p>This method builds its own HTTP client rather than going through
 * {@code ConverterService}, so a stub {@code HttpServer} on a loopback port
 * stands in for the converter — mocking the service would miss the call
 * entirely. Started in a static initialiser because
 * {@code @DynamicPropertySource} is read when each {@code @Nested} class's
 * context loads, which is before the outer {@code @BeforeAll} would run.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActingAs(value = User.Role.ENGINEER, username = Viewer3DDispatchTest.USERNAME)
@DisplayName("opening a model through the viewer")
class Viewer3DDispatchTest {

    static final String USERNAME = "viewer-3d-dispatch-user";

    private record Reply(int status, String contentType, byte[] body) {
        static Reply json(String body) {
            return new Reply(200, "application/json", body.getBytes(StandardCharsets.UTF_8));
        }
        static Reply mesh(byte[] body) {
            return new Reply(200, "model/gltf-binary", body);
        }
        static Reply meshWithNoType(byte[] body) {
            return new Reply(200, null, body);
        }
    }

    private static final AtomicReference<Reply> nextReply =
        new AtomicReference<>(Reply.json("{\"success\":true}"));

    private static HttpServer converter;

    static {
        try {
            converter = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            converter.createContext("/", exchange -> {
                Reply reply = nextReply.get();
                if (reply.contentType() != null) {
                    exchange.getResponseHeaders().set("Content-Type", reply.contentType());
                }
                exchange.sendResponseHeaders(reply.status(), reply.body().length);
                try (var out = exchange.getResponseBody()) {
                    out.write(reply.body());
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
        nextReply.set(Reply.json("{\"success\":true}"));
        owner = userRepo.findByUsername(USERNAME).orElseGet(() ->
            userRepo.save(User.builder()
                .username(USERNAME).email("viewer-3d-dispatch@example.test")
                .password("{noop}irrelevant").role(User.Role.ENGINEER).build()));
        project = projectRepo.save(Project.builder()
            .name("Models " + System.nanoTime())
            .phase(Project.ProjectPhase.DESIGN).build());
    }

    private Document model(String fileName, String mediaType) throws IOException {
        Path path = storage.resolve(UUID.randomUUID() + "_" + fileName);
        Files.write(path, new byte[] { 'g', 'l', 'T', 'F', 2, 0, 0, 0 });
        return documentRepo.save(Document.builder()
            .name(fileName.replaceAll("\\.[^.]+$", ""))
            .fileName(fileName).fileType(mediaType)
            .filePath(path.toString()).fileSize(Files.size(path))
            .documentType(Document.DocumentType.BIM_MODEL)
            .project(project).uploadedBy(owner).build());
    }

    private org.springframework.test.web.servlet.ResultActions open(Document document)
            throws Exception {
        return mockMvc.perform(get("/api/viewer/" + document.getId()));
    }

    /** A row whose file is recorded but was never written. */
    private Document withMissingFile() {
        return documentRepo.save(Document.builder()
            .name("Detached").fileName("tower.ifc").fileType("application/ifc")
            .filePath(storage.resolve("never-written.ifc").toAbsolutePath().toString())
            .documentType(Document.DocumentType.BIM_MODEL)
            .project(project).uploadedBy(owner).build());
    }

    @Nested
    @DisplayName("a mesh the browser can load directly")
    class MeshFormats {

        @ParameterizedTest(name = "a .{0} comes back as bytes")
        @ValueSource(strings = {"glb", "gltf", "obj", "stl", "ply", "dae"})
        @DisplayName("every mesh format is served as bytes rather than JSON")
        void meshFormatsAreServedAsBytes(String extension) throws Exception {
            // A viewer handed JSON where it expected a mesh shows an empty
            // canvas and says nothing about why.
            nextReply.set(Reply.mesh(new byte[] { 1, 2, 3, 4 }));

            open(model("tower." + extension, "application/octet-stream"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-3D-Format", extension));
        }

        @Test
        @DisplayName("the format header is readable by the browser's script")
        void formatHeaderIsExposed() throws Exception {
            // Without the expose header a cross-origin viewer cannot read
            // X-3D-Format at all, so it cannot choose a loader.
            nextReply.set(Reply.mesh(new byte[] { 1, 2, 3 }));

            open(model("tower.glb", "application/octet-stream"))
                .andExpect(header().string("Access-Control-Expose-Headers", "X-3D-Format"));
        }

        @Test
        @DisplayName("the bytes the converter produced are what the reader receives")
        void bytesArePassedThrough() throws Exception {
            nextReply.set(Reply.mesh(new byte[] { 9, 8, 7, 6, 5 }));

            byte[] served = open(model("tower.glb", "application/octet-stream"))
                .andReturn().getResponse().getContentAsByteArray();

            assertThat(served).containsExactly(9, 8, 7, 6, 5);
        }

        @Test
        @DisplayName("the content type the converter named is carried through")
        void contentTypeIsCarriedThrough() throws Exception {
            nextReply.set(Reply.mesh(new byte[] { 1 }));

            open(model("tower.glb", "application/octet-stream"))
                .andExpect(header().string("Content-Type", "model/gltf-binary"));
        }

        @Test
        @DisplayName("a converter that names no type still produces a servable response")
        void missingContentTypeFallsBack() throws Exception {
            // The fallback exists because a response with no Content-Type
            // would otherwise fail to build at all, losing a conversion that
            // actually succeeded.
            nextReply.set(Reply.meshWithNoType(new byte[] { 1, 2 }));

            open(model("tower.stl", "application/octet-stream"))
                .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("an IFC, which comes back as a structure")
    class IfcModels {

        @Test
        @DisplayName("a successful conversion is answered with the converter's own JSON")
        void successIsPassedThrough() throws Exception {
            nextReply.set(Reply.json(
                "{\"success\":true,\"type\":\"ifc3d\",\"elementCount\":1420}"));

            open(model("tower.ifc", "application/ifc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.elementCount").value(1420));
        }

        @Test
        @DisplayName("a failed conversion is reported as an error with its reason")
        void failureCarriesItsReason() throws Exception {
            // The reader needs to know the model could not be read, and why —
            // an empty canvas with no message is the worst outcome.
            nextReply.set(Reply.json(
                "{\"success\":false,\"error\":\"Unsupported IFC4X3 schema\"}"));

            open(model("tower.ifc", "application/ifc"))
                .andExpect(jsonPath("$.type").value("error"))
                .andExpect(jsonPath("$.error").value("Unsupported IFC4X3 schema"));
        }

        @Test
        @DisplayName("a failure with no reason still says something")
        void failureWithoutAReasonSaysSomething() throws Exception {
            nextReply.set(Reply.json("{\"success\":false}"));

            open(model("tower.ifc", "application/ifc"))
                .andExpect(jsonPath("$.type").value("error"))
                .andExpect(jsonPath("$.error").value("IFC conversion failed"));
        }

        @Test
        @DisplayName("an answer that says nothing about success is treated as failure")
        void silenceIsTreatedAsFailure() throws Exception {
            // The opposite default from the comparison endpoint, and correct
            // here: a conversion that did not report success produced no
            // geometry, and showing an empty model as a success is worse than
            // saying it failed.
            nextReply.set(Reply.json("{\"elementCount\":0}"));

            open(model("tower.ifc", "application/ifc"))
                .andExpect(jsonPath("$.type").value("error"));
        }
    }

    @Nested
    @DisplayName("a format no open toolchain reads")
    class RevitModels {

        @ParameterizedTest(name = "a .{0} says it must be exported first")
        @ValueSource(strings = {"rvt", "rfa"})
        @DisplayName("a Revit file is refused before the converter is troubled")
        void revitSaysExportFirst(String extension) throws Exception {
            // Answered without calling the converter at all: there is nothing
            // it could do, and a 180-second timeout spent finding that out is
            // a 180-second wait for the reader.
            open(model("tower." + extension, "application/octet-stream"))
                .andExpect(jsonPath("$.type").value("revit_binary"));
        }

        @Test
        @DisplayName("the reply names the file, so the message is actionable")
        void revitReplyNamesTheFile() throws Exception {
            open(model("tower.rvt", "application/octet-stream"))
                .andExpect(jsonPath("$.fileName").value("tower.rvt"));
        }
    }

    @Nested
    @DisplayName("when the conversion service is not there")
    class ConverterTrouble {

        @Test
        @DisplayName("an unreadable answer is reported as an error, not a 500")
        void unreadableAnswerIsAnError() throws Exception {
            // A converter behind a proxy returns an HTML error page, and a
            // stack trace in the viewer is no use to anybody.
            nextReply.set(new Reply(200, "text/html",
                "<html>Bad Gateway</html>".getBytes(StandardCharsets.UTF_8)));

            open(model("tower.ifc", "application/ifc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("error"));
        }

        @Test
        @DisplayName("a document whose file is missing is reported as an error")
        void missingFileIsReportedAsAnError() throws Exception {
            // Answered inside the viewer's own envelope rather than as a
            // status code, because the client switches on `type` — the same
            // coupling that keeps the SVG route returning JSON.
            open(withMissingFile()).andExpect(jsonPath("$.type").value("error"));
        }

        @Test
        @DisplayName("the error does not hand the caller the storage path")
        void missingFileDoesNotLeakThePath() throws Exception {
            // It did: the message was "File not found on disk: " plus the
            // absolute path, so an ordinary missing file published the
            // deployment's storage layout to anybody who could open a
            // document (§5.13.13).
            Document detached = withMissingFile();

            String body = open(detached).andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain(detached.getFilePath());
            assertThat(body).doesNotContain(storage.toString());
        }

        @Test
        @DisplayName("the error still says what to do next")
        void missingFileSaysWhatToDo() throws Exception {
            // Removing the path must not leave a message nobody can act on
            // (§1.4): still uploading and already removed are different
            // situations, and the trace id is what support will ask for.
            String body = open(withMissingFile()).andReturn().getResponse().getContentAsString();

            assertThat(body).contains("trace id");
        }

        @Test
        @DisplayName("a document with no file path recorded at all is reported too")
        void noPathRecordedIsReported() throws Exception {
            Document detached = documentRepo.save(Document.builder()
                .name("Placeholder").fileName("tower.ifc").fileType("application/ifc")
                .filePath(null)
                .documentType(Document.DocumentType.BIM_MODEL)
                .project(project).uploadedBy(owner).build());

            open(detached).andExpect(jsonPath("$.type").value("error"));
        }

        @Test
        @DisplayName("a document that does not exist is a 404")
        void unknownDocumentIsNotFound() throws Exception {
            mockMvc.perform(get("/api/viewer/987654321"))
                .andExpect(status().isNotFound());
        }
    }
}
