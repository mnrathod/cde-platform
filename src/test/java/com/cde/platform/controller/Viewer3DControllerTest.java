package com.cde.platform.controller;

import com.cde.platform.model.Document;
import com.cde.platform.model.Project;
import com.cde.platform.model.User;
import com.cde.platform.repository.DocumentRepository;
import com.cde.platform.repository.ProjectRepository;
import com.cde.platform.repository.UserRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The three routes the 3D viewer uses, and what each does when the
 * conversion service is not there.
 *
 * <p>This controller talks to the converter over plain HTTP rather than
 * through {@code ConverterService}, so a stub server stands in for it: a
 * JDK {@code HttpServer} on a loopback port, pointed at by
 * {@code cde.converter.url}. That keeps the real request, the real timeout
 * handling and the real response parsing in the test, which matters here
 * because the geometry route's whole point is that it forwards bytes without
 * decoding them (§7.7) — a mocked client would step around exactly the
 * behaviour under test.
 *
 * <p>The synthetic-tree cases carry the most weight. §1A.4 makes the model
 * tree the accessible equivalent of the canvas, which means for a keyboard
 * or screen-reader user it is not a fallback view — it is the view. Handing
 * back an invented list of element classes with no marker would present a
 * generic outline as the building's contents, and the reader has no way to
 * tell.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("opening a 3D model")
class Viewer3DControllerTest {

    private static final String USERNAME = "viewer3d-user";

    /** What the stub converter answers with next, set per test. */
    private static final AtomicReference<Reply> nextReply = new AtomicReference<>();

    private static HttpServer converter;

    private record Reply(int status, String contentType, byte[] body) {
        static Reply json(String body) {
            return new Reply(200, "application/json",
                             body.getBytes(StandardCharsets.UTF_8));
        }

        static Reply bytes(byte[] body) {
            return new Reply(200, "application/octet-stream", body);
        }
    }

    /*
     * Started in a static initialiser rather than from @BeforeAll.
     *
     * `@DynamicPropertySource` is evaluated when the application context
     * loads, and each @Nested class loads its own — which happens before the
     * outer class's @BeforeAll has run, so the port would be read off a null
     * server and every nested context would fail to start.
     */
    static {
        try {
            converter = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            converter.createContext("/", exchange -> {
                Reply reply = nextReply.get();
                exchange.getResponseHeaders().set("Content-Type", reply.contentType());
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
        nextReply.set(Reply.json("{}"));
        owner = userRepo.findByUsername(USERNAME).orElseGet(() ->
            userRepo.save(User.builder()
                .username(USERNAME).email("viewer3d@example.com")
                .password("x").role(User.Role.ENGINEER).build()));

        project = projectRepo.save(Project.builder()
            .name("Models").description("d")
            .phase(Project.ProjectPhase.DESIGN).build());
    }

    private Document model(String fileName, String mediaType, String body) throws IOException {
        Path path = storage.resolve(UUID.randomUUID() + "_" + fileName);
        Files.writeString(path, body);
        return documentRepo.save(Document.builder()
            .name(fileName.replaceAll("\\.[^.]+$", ""))
            .fileName(fileName).fileType(mediaType)
            .filePath(path.toString()).fileSize(Files.size(path))
            .documentType(Document.DocumentType.BIM_MODEL)
            .project(project).uploadedBy(owner).build());
    }

    private Document withNoFile() {
        return documentRepo.save(Document.builder()
            .name("Detached").fileName("model.ifc").fileType("application/ifc")
            .filePath(null).documentType(Document.DocumentType.BIM_MODEL)
            .project(project).uploadedBy(owner).build());
    }

    private ResultActions open(Document document) throws Exception {
        return mockMvc.perform(get("/api/viewer3d/{id}", document.getId()));
    }

    private ResultActions tree(Document document) throws Exception {
        return mockMvc.perform(get("/api/viewer3d/{id}/tree", document.getId()));
    }

    private ResultActions geometry(Document document) throws Exception {
        return mockMvc.perform(get("/api/viewer3d/{id}/geometry", document.getId()));
    }

    // ── Deciding what the file is ─────────────────────────────────────────

    @Nested
    @DisplayName("deciding what the model is")
    class Dispatch {

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("a mesh is served as its own bytes")
        void meshIsServedDirectly() throws Exception {
            open(model("tower.glb", "model/gltf-binary", "glTFDATA"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-3D-Format", "glb"));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("a Revit file says it cannot be opened directly")
        void revitIsNamed() throws Exception {
            open(model("tower.rvt", "application/octet-stream", "RVT"))
                .andExpect(jsonPath("$.type").value("revit_binary"));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("a Revit family file is the same answer")
        void revitFamilyIsNamed() throws Exception {
            open(model("door.rfa", "application/octet-stream", "RFA"))
                .andExpect(jsonPath("$.type").value("revit_binary"));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("an IFC goes to the converter")
        void ifcIsConverted() throws Exception {
            nextReply.set(Reply.json("{\"success\":true,\"meshes\":[]}"));

            open(model("tower.ifc", "application/ifc", "ISO-10303-21;"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("a format nothing here can open names the extension")
        void unsupportedFormatIsNamed() throws Exception {
            open(model("drawing.dwg", "image/vnd.dwg", "AC1032"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error")
                    .value(org.hamcrest.Matchers.containsString(".dwg")));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("a document that does not exist is a 404")
        void unknownDocumentIsNotFound() throws Exception {
            mockMvc.perform(get("/api/viewer3d/{id}", 9_999_999L))
                .andExpect(status().isNotFound());
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("a document with no stored file says so")
        void documentWithNoFileIsReported() throws Exception {
            open(withNoFile())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error")
                    .value(org.hamcrest.Matchers.containsString("No file path")));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("a document whose file has gone from disk says that instead")
        void missingFileIsReported() throws Exception {
            Document gone = model("tower.ifc", "application/ifc", "ISO-10303-21;");
            Files.delete(Path.of(gone.getFilePath()));

            open(gone)
                .andExpect(jsonPath("$.error")
                    .value(org.hamcrest.Matchers.containsString("File not found")));
        }
    }

    // ── The model tree, which is the accessible view ──────────────────────

    @Nested
    @DisplayName("the model tree")
    class Tree {

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("returns the hierarchy the converter extracted")
        void returnsTheRealHierarchy() throws Exception {
            nextReply.set(Reply.json("""
                [{"id":"1","name":"Level 00","type":"IfcBuildingStorey","children":[]}]"""));

            tree(model("tower.ifc", "application/ifc", "ISO-10303-21;"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Level 00"));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("does not mark an extracted hierarchy as synthetic")
        void realHierarchyIsNotMarked() throws Exception {
            nextReply.set(Reply.json("""
                [{"id":"1","name":"Level 00","type":"IfcBuildingStorey","children":[]}]"""));

            tree(model("tower.ifc", "application/ifc", "ISO-10303-21;"))
                .andExpect(jsonPath("$[0].synthetic").doesNotExist());
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("marks a placeholder outline as synthetic when the converter fails")
        void placeholderIsMarked() throws Exception {
            // §1A.4 makes this tree the accessible equivalent of the canvas,
            // so for a keyboard or screen-reader user it is the view, not a
            // fallback. Without the marker an invented list of element
            // classes reads as the building's contents, and the reader has
            // no way to tell.
            nextReply.set(new Reply(500, "text/plain", "boom".getBytes(StandardCharsets.UTF_8)));

            tree(model("tower.ifc", "application/ifc", "ISO-10303-21;"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].synthetic").value(true));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("names the placeholder root after the document")
        void placeholderIsNamedAfterTheDocument() throws Exception {
            nextReply.set(new Reply(500, "text/plain", "boom".getBytes(StandardCharsets.UTF_8)));

            tree(model("tower.ifc", "application/ifc", "ISO-10303-21;"))
                .andExpect(jsonPath("$[0].name").value("tower"));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("gives the placeholder something to navigate")
        void placeholderHasChildren() throws Exception {
            // An empty tree is not an accessible equivalent of anything.
            nextReply.set(new Reply(500, "text/plain", "boom".getBytes(StandardCharsets.UTF_8)));

            tree(model("tower.ifc", "application/ifc", "ISO-10303-21;"))
                .andExpect(jsonPath("$[0].children.length()")
                    .value(org.hamcrest.Matchers.greaterThan(0)));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("returns an empty tree for a document with no file, not a placeholder")
        void noFileMeansNoTree() throws Exception {
            // There is no model, so there is nothing to outline. A
            // placeholder here would describe a building that was never
            // uploaded.
            tree(withNoFile())
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("a document that does not exist is a 404")
        void unknownDocumentIsNotFound() throws Exception {
            mockMvc.perform(get("/api/viewer3d/{id}/tree", 9_999_999L))
                .andExpect(status().isNotFound());
        }
    }

    // ── The geometry buffer ───────────────────────────────────────────────

    @Nested
    @DisplayName("the geometry buffer")
    class Geometry {

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("forwards the converter's bytes as bytes")
        void forwardsBytes() throws Exception {
            // Binary rather than JSON because the arrays are already
            // numbers; base64 would cost four bytes of transfer for every
            // three of payload on a building-sized model.
            byte[] container = "CDEG\u0001\u0000\u0000\u0000payload".getBytes(StandardCharsets.UTF_8);
            nextReply.set(Reply.bytes(container));

            geometry(model("tower.ifc", "application/ifc", "ISO-10303-21;"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/octet-stream"))
                .andExpect(content().bytes(container));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("never caches a geometry response")
        void geometryIsNotCached() throws Exception {
            nextReply.set(Reply.bytes("CDEG".getBytes(StandardCharsets.UTF_8)));

            geometry(model("tower.ifc", "application/ifc", "ISO-10303-21;"))
                .andExpect(header().string("Cache-Control", "no-store"));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("passes a JSON explanation through as JSON")
        void forwardsJsonExplanation() throws Exception {
            // A model the converter cannot read comes back as JSON with a
            // 200, which is why the route's documentation tells clients to
            // check the content type before parsing.
            nextReply.set(Reply.json("{\"success\":false,\"error\":\"no geometry\"}"));

            geometry(model("tower.ifc", "application/ifc", "ISO-10303-21;"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("does not ask the converter to read a mesh as IFC")
        void meshIsNotSentToTheConverter() throws Exception {
            // Only IFC produces extracted geometry. Asking saves nothing and
            // guarantees a failure, so the client is told to get the real
            // reason from the JSON sibling instead.
            geometry(model("tower.glb", "model/gltf-binary", "glTFDATA"))
                .andExpect(jsonPath("$.error").value("not_extracted_geometry"));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("accepts an IFC declared only by its media type")
        void ifcRecognisedByMediaType() throws Exception {
            nextReply.set(Reply.bytes("CDEG".getBytes(StandardCharsets.UTF_8)));

            geometry(model("download", "application/ifc", "ISO-10303-21;"))
                .andExpect(header().string("Content-Type", "application/octet-stream"));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("a document with no stored file says so rather than calling out")
        void noFileIsReported() throws Exception {
            geometry(withNoFile())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("File not found"));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("a document that does not exist is a 404")
        void unknownDocumentIsNotFound() throws Exception {
            mockMvc.perform(get("/api/viewer3d/{id}/geometry", 9_999_999L))
                .andExpect(status().isNotFound());
        }
    }
}
