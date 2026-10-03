package com.cde.platform.controller;

import com.cde.platform.support.ActingAs;
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

import static org.assertj.core.api.Assertions.assertThat;
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

    /** A model stored without a filename, or without a declared type, or both. */
    private Document withMediaTypeOnly(String fileName, String mediaType) throws IOException {
        Path path = storage.resolve(UUID.randomUUID() + "_model");
        Files.writeString(path, "ISO-10303-21;");
        return documentRepo.save(Document.builder()
            .name("Unnamed").fileName(fileName).fileType(mediaType)
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
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("a mesh is served as its own bytes")
        void meshIsServedDirectly() throws Exception {
            open(model("tower.glb", "model/gltf-binary", "glTFDATA"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-3D-Format", "glb"));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("a Revit file says it cannot be opened directly")
        void revitIsNamed() throws Exception {
            open(model("tower.rvt", "application/octet-stream", "RVT"))
                .andExpect(jsonPath("$.type").value("revit_binary"));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("a Revit family file is the same answer")
        void revitFamilyIsNamed() throws Exception {
            open(model("door.rfa", "application/octet-stream", "RFA"))
                .andExpect(jsonPath("$.type").value("revit_binary"));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("an IFC goes to the converter")
        void ifcIsConverted() throws Exception {
            nextReply.set(Reply.json("{\"success\":true,\"meshes\":[]}"));

            open(model("tower.ifc", "application/ifc", "ISO-10303-21;"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("a format nothing here can open names the extension")
        void unsupportedFormatIsNamed() throws Exception {
            open(model("drawing.dwg", "image/vnd.dwg", "AC1032"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error")
                    .value(org.hamcrest.Matchers.containsString(".dwg")));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("a document that does not exist is a 404")
        void unknownDocumentIsNotFound() throws Exception {
            mockMvc.perform(get("/api/viewer3d/{id}", 9_999_999L))
                .andExpect(status().isNotFound());
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("a document with no stored file says so")
        void documentWithNoFileIsReported() throws Exception {
            open(withNoFile())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error")
                    .value(org.hamcrest.Matchers.containsString("nothing to open")));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("a document whose file has gone from disk says that instead")
        void missingFileIsReported() throws Exception {
            Document gone = model("tower.ifc", "application/ifc", "ISO-10303-21;");
            Files.delete(Path.of(gone.getFilePath()));

            open(gone)
                .andExpect(jsonPath("$.error")
                    .value(org.hamcrest.Matchers.containsString("not in storage")));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("neither refusal names where the deployment keeps its files")
        void refusalsDoNotNameTheStoragePath() throws Exception {
            // This route answered "File not found: " + path, which put the
            // storage root, the tenant prefix and the generated object name
            // into a response any viewer displays (§5.13.13). The path is no
            // use to the person reading it — they cannot reach that
            // filesystem — and it is useful to someone probing the deployment.
            Document gone = model("tower.ifc", "application/ifc", "ISO-10303-21;");
            String leaked = storage.toString();
            Files.delete(Path.of(gone.getFilePath()));

            assertThat(open(gone).andReturn().getResponse().getContentAsString())
                .doesNotContain(leaked);
            assertThat(open(withNoFile()).andReturn().getResponse().getContentAsString())
                .doesNotContain(leaked);
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("a model with no filename recorded is read by its media type")
        void mediaTypeDecidesWhenThereIsNoFilename() throws Exception {
            // SCIM and API uploads do not always carry a filename, and a null
            // here used to be the difference between opening the model and a
            // NullPointerException on the extension.
            nextReply.set(Reply.json("{\"success\":true,\"meshes\":[]}"));

            open(withMediaTypeOnly(null, "application/ifc"))
                .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("a filename with no extension falls back to the media type")
        void noExtensionFallsBackToTheMediaType() throws Exception {
            nextReply.set(Reply.json("{\"success\":true,\"meshes\":[]}"));

            open(model("tower", "application/ifc", "ISO-10303-21;"))
                .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("a STEP file declared by media type goes to the converter")
        void stepIsConverted() throws Exception {
            // STEP shares the IFC toolchain, and nothing in the extension says
            // so — .stp and .step both appear, and neither is in the mesh list.
            nextReply.set(Reply.json("{\"success\":true,\"meshes\":[]}"));

            open(model("assembly.stp", "application/step", "ISO-10303-21;"))
                .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("a model with neither a filename nor a media type is named unsupported")
        void nothingToGoOnIsUnsupported() throws Exception {
            // Not an error: the document exists and cannot be opened, which is
            // what the viewer needs to be told so it can offer a download.
            open(withMediaTypeOnly(null, null))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error")
                    .value(org.hamcrest.Matchers.containsString("Unsupported")));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("a conversion the converter itself refused passes its reason on")
        void converterRefusalIsPassedOn() throws Exception {
            // The reason is the converter's, and replacing it with a generic
            // sentence leaves the user with nothing to act on (§1.4).
            nextReply.set(Reply.json(
                "{\"success\":false,\"error\":\"schema IFC4X3 is not supported\"}"));

            open(model("tower.ifc", "application/ifc", "ISO-10303-21;"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("schema IFC4X3 is not supported"));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("a refusal with no reason given still says something")
        void converterRefusalWithoutAReason() throws Exception {
            nextReply.set(Reply.json("{\"success\":false}"));

            open(model("tower.ifc", "application/ifc", "ISO-10303-21;"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value(
                    org.hamcrest.Matchers.containsString("conversion failed")));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("a reply that is not JSON at all is reported, not thrown")
        void unreadableConverterReply() throws Exception {
            // A proxy error page where JSON was expected. The user gets a
            // viewer that says it could not open the model, not a 500.
            nextReply.set(new Reply(200, "text/html",
                "<html>502 Bad Gateway</html>".getBytes(StandardCharsets.UTF_8)));

            open(model("tower.ifc", "application/ifc", "ISO-10303-21;"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));
        }
    }

    // ── The model tree, which is the accessible view ──────────────────────

    @Nested
    @DisplayName("the model tree")
    class Tree {

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("returns the hierarchy the converter extracted")
        void returnsTheRealHierarchy() throws Exception {
            nextReply.set(Reply.json("""
                [{"id":"1","name":"Level 00","type":"IfcBuildingStorey","children":[]}]"""));

            tree(model("tower.ifc", "application/ifc", "ISO-10303-21;"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Level 00"));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("does not mark an extracted hierarchy as synthetic")
        void realHierarchyIsNotMarked() throws Exception {
            nextReply.set(Reply.json("""
                [{"id":"1","name":"Level 00","type":"IfcBuildingStorey","children":[]}]"""));

            tree(model("tower.ifc", "application/ifc", "ISO-10303-21;"))
                .andExpect(jsonPath("$[0].synthetic").doesNotExist());
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
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
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("names the placeholder root after the document")
        void placeholderIsNamedAfterTheDocument() throws Exception {
            nextReply.set(new Reply(500, "text/plain", "boom".getBytes(StandardCharsets.UTF_8)));

            tree(model("tower.ifc", "application/ifc", "ISO-10303-21;"))
                .andExpect(jsonPath("$[0].name").value("tower"));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("gives the placeholder something to navigate")
        void placeholderHasChildren() throws Exception {
            // An empty tree is not an accessible equivalent of anything.
            nextReply.set(new Reply(500, "text/plain", "boom".getBytes(StandardCharsets.UTF_8)));

            tree(model("tower.ifc", "application/ifc", "ISO-10303-21;"))
                .andExpect(jsonPath("$[0].children.length()")
                    .value(org.hamcrest.Matchers.greaterThan(0)));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
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
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
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
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
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
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("never caches a geometry response")
        void geometryIsNotCached() throws Exception {
            nextReply.set(Reply.bytes("CDEG".getBytes(StandardCharsets.UTF_8)));

            geometry(model("tower.ifc", "application/ifc", "ISO-10303-21;"))
                .andExpect(header().string("Cache-Control", "no-store"));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
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
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("does not ask the converter to read a mesh as IFC")
        void meshIsNotSentToTheConverter() throws Exception {
            // Only IFC produces extracted geometry. Asking saves nothing and
            // guarantees a failure, so the client is told to get the real
            // reason from the JSON sibling instead.
            geometry(model("tower.glb", "model/gltf-binary", "glTFDATA"))
                .andExpect(jsonPath("$.error").value("not_extracted_geometry"));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("accepts an IFC declared only by its media type")
        void ifcRecognisedByMediaType() throws Exception {
            nextReply.set(Reply.bytes("CDEG".getBytes(StandardCharsets.UTF_8)));

            geometry(model("download", "application/ifc", "ISO-10303-21;"))
                .andExpect(header().string("Content-Type", "application/octet-stream"));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("a document with no stored file says so rather than calling out")
        void noFileIsReported() throws Exception {
            geometry(withNoFile())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("File not found"));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("a document that does not exist is a 404")
        void unknownDocumentIsNotFound() throws Exception {
            mockMvc.perform(get("/api/viewer3d/{id}/geometry", 9_999_999L))
                .andExpect(status().isNotFound());
        }
    }

    // ── The media type each mesh format is served as ──────────────────────

    @Nested
    @DisplayName("serving a mesh")
    class MeshMediaTypes {

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("a binary glTF is served as binary glTF")
        void glbHasItsOwnType() throws Exception {
            open(model("tower.glb", "model/gltf-binary", "glTF"))
                .andExpect(content().contentTypeCompatibleWith("model/gltf-binary"));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("a text glTF is served as JSON glTF, not as binary")
        void gltfHasItsOwnType() throws Exception {
            // three.js picks its loader off the response type, so serving a
            // .gltf as octet-stream is a model that silently will not load.
            open(model("tower.gltf", "model/gltf+json", "{\"asset\":{}}"))
                .andExpect(content().contentTypeCompatibleWith("model/gltf+json"));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("an OBJ is served as text, because that is what it is")
        void objIsText() throws Exception {
            open(model("tower.obj", "text/plain", "v 0 0 0"))
                .andExpect(content().contentTypeCompatibleWith("text/plain"));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("a COLLADA file is served as XML")
        void daeIsXml() throws Exception {
            open(model("tower.dae", "model/vnd.collada+xml", "<COLLADA/>"))
                .andExpect(content().contentTypeCompatibleWith("text/xml"));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("an STL falls back to octet-stream")
        void stlFallsBackToBytes() throws Exception {
            open(model("tower.stl", "application/sla", "solid tower"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-3D-Format", "stl"));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("a PLY falls back to octet-stream too")
        void plyFallsBackToBytes() throws Exception {
            open(model("tower.ply", "application/octet-stream", "ply"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-3D-Format", "ply"));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("the format header is readable from another origin")
        void formatHeaderIsExposedCrossOrigin() throws Exception {
            // A viewer embedded on a customer's own page reads this header to
            // choose a loader, and a browser hides every header not named in
            // Access-Control-Expose-Headers.
            open(model("tower.glb", "model/gltf-binary", "glTF"))
                .andExpect(header().string("Access-Control-Expose-Headers", "X-3D-Format"));
        }
    }

    // ── The geometry route's own dispatch ─────────────────────────────────

    @Nested
    @DisplayName("deciding whether geometry can be extracted")
    class GeometryDispatch {

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("a model with no filename is judged by its media type")
        void mediaTypeDecidesWithoutAFilename() throws Exception {
            nextReply.set(Reply.bytes(new byte[]{1, 2, 3, 4}));

            mockMvc.perform(get("/api/viewer3d/{id}/geometry",
                    withMediaTypeOnly(null, "application/ifc").getId()))
                .andExpect(status().isOk());
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("a filename with no extension is judged by its media type")
        void noExtensionIsJudgedByMediaType() throws Exception {
            nextReply.set(Reply.bytes(new byte[]{1, 2, 3, 4}));

            geometry(model("tower", "application/ifc", "ISO-10303-21;"))
                .andExpect(status().isOk());
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("a STEP file is extracted, because it shares the IFC toolchain")
        void stepGoesToTheExtractor() throws Exception {
            nextReply.set(Reply.bytes(new byte[]{1, 2, 3, 4}));

            geometry(model("assembly.stp", "application/step", "ISO-10303-21;"))
                .andExpect(status().isOk());
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("a model with nothing to go on is refused without calling out")
        void nothingToGoOnIsRefusedLocally() throws Exception {
            // Asking the converter to read a mesh as IFC is a round trip with a
            // guaranteed failure at the end of it.
            mockMvc.perform(get("/api/viewer3d/{id}/geometry",
                    withMediaTypeOnly(null, null).getId()))
                .andExpect(jsonPath("$.error").value("not_extracted_geometry"));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("a document whose file has gone says so without naming the path")
        void missingGeometryFileDoesNotNameThePath() throws Exception {
            Document gone = model("tower.ifc", "application/ifc", "ISO-10303-21;");
            Files.delete(Path.of(gone.getFilePath()));

            assertThat(geometry(gone).andReturn().getResponse().getContentAsString())
                .doesNotContain(storage.toString());
        }
    }
}
