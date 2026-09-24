package com.cde.platform.controller;

import com.cde.platform.model.Document;
import com.cde.platform.model.Project;
import com.cde.platform.model.User;
import com.cde.platform.repository.DocumentRepository;
import com.cde.platform.repository.ProjectRepository;
import com.cde.platform.repository.UserRepository;
import com.cde.platform.service.ConverterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deciding what the viewer is being asked to open.
 *
 * <p>One endpoint answers for every format the product accepts, and its
 * reply changes shape depending on what it found: SVG markup for a drawing,
 * a pointer to the bytes for a PDF, raw bytes for an image or a mesh, and
 * one of several explanations for anything that cannot be shown. The client
 * reads `type` first and branches on it, so the dispatch is a contract and
 * not an implementation detail.
 *
 * <p>Every case here is driven by what the document says it is — its
 * extension and its stored media type — because that is the only input the
 * dispatch has, and the two disagree often enough in practice that both
 * routes to each branch are worth holding.
 *
 * <p>Note the thing the endpoint's own documentation calls a defect and
 * keeps: the variants that report a problem come back with 200, not with a
 * 4xx. Correcting it is a coordinated change on both sides, so the cases
 * below assert the behaviour as it ships rather than as it should be.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("opening a document in the viewer")
class ViewerFormatDispatchTest {

    private static final String USERNAME = "viewer-dispatch-user";

    @Autowired MockMvc mockMvc;
    @Autowired DocumentRepository documentRepo;
    @Autowired ProjectRepository projectRepo;
    @Autowired UserRepository userRepo;

    @MockitoBean ConverterService converter;

    @TempDir Path storage;

    private final ObjectMapper mapper = new ObjectMapper();
    private Project project;
    private User owner;

    @BeforeEach
    void setUp() {
        owner = userRepo.findByUsername(USERNAME).orElseGet(() ->
            userRepo.save(User.builder()
                .username(USERNAME).email("viewer-dispatch@example.com")
                .password("x").role(User.Role.ENGINEER).build()));

        project = projectRepo.save(Project.builder()
            .name("Dispatch").description("d")
            .phase(Project.ProjectPhase.DESIGN).build());
    }

    /** A stored document with the given name and media type. */
    private Document document(String fileName, String mediaType, String body) throws IOException {
        Path path = storage.resolve(java.util.UUID.randomUUID() + "_" + fileName);
        Files.writeString(path, body);
        return documentRepo.save(Document.builder()
            .name(fileName.replaceAll("\\.[^.]+$", ""))
            .fileName(fileName).fileType(mediaType)
            .filePath(path.toString()).fileSize(Files.size(path))
            .documentType(Document.DocumentType.DRAWING)
            .project(project).uploadedBy(owner).build());
    }

    private org.springframework.test.web.servlet.ResultActions open(Document document)
            throws Exception {
        return mockMvc.perform(get("/api/viewer/{id}", document.getId()));
    }

    // ── Drawings ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("a drawing")
    class Drawings {

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("stored as markup is returned without touching the converter")
        void storedVectorDataIsReturnedDirectly() throws Exception {
            // A drawing already rendered once is kept, so opening it again
            // is a database read rather than a conversion.
            Document drawing = document("plan.dxf", "image/vnd.dxf", "0\nSECTION");
            drawing.setVectorData("<svg>stored</svg>");
            drawing.setDrawingNumber("A-101");
            drawing.setRevision("P02");
            documentRepo.save(drawing);

            open(drawing)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("svg"))
                .andExpect(jsonPath("$.content").value("<svg>stored</svg>"))
                .andExpect(jsonPath("$.drawingNumber").value("A-101"))
                .andExpect(jsonPath("$.revision").value("P02"));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("is converted when no markup has been stored yet")
        void dxfIsConverted() throws Exception {
            when(converter.convert(any(), anyString())).thenReturn(
                new ConverterService.ConvertResult(true, "<svg>fresh</svg>", null, null));

            open(document("plan.dxf", "image/vnd.dxf", "0\nSECTION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("svg"))
                .andExpect(jsonPath("$.content").value("<svg>fresh</svg>"))
                .andExpect(jsonPath("$.renderedBy").value("java-fallback"));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("says which renderer drew it when the converter reported one")
        void namesTheRenderer() throws Exception {
            // The viewer shows this, and it is the difference between a
            // drawing the full renderer produced and one the fallback did.
            when(converter.convert(any(), anyString())).thenReturn(
                new ConverterService.ConvertResult(
                    true, "<svg/>", null, mapper.readTree("{\"convertedBy\":\"ezdxf\"}")));

            open(document("plan.dxf", "image/vnd.dxf", "0\nSECTION"))
                .andExpect(jsonPath("$.renderedBy").value("ezdxf"));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("is recognised by media type when the name says nothing")
        void dxfIsRecognisedByMediaType() throws Exception {
            // Uploads arrive named "download" often enough that the media
            // type has to be a route to the same branch.
            when(converter.convert(any(), anyString())).thenReturn(
                new ConverterService.ConvertResult(true, "<svg/>", null, null));

            open(document("download", "image/vnd.dxf", "0\nSECTION"))
                .andExpect(jsonPath("$.type").value("svg"));
        }
    }

    // ── A DWG the converter could not open ────────────────────────────────

    @Nested
    @DisplayName("a DWG that could not be converted")
    class UnconvertedDwg {

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("reports the version so the reader knows what they have")
        void reportsTheVersion() throws Exception {
            when(converter.convert(any(), anyString())).thenReturn(
                new ConverterService.ConvertResult(
                    false, null, "DWG_BINARY:AutoCAD 2018", null));

            open(document("plan.dwg", "image/vnd.dwg", "AC1032"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("dwg_binary"))
                .andExpect(jsonPath("$.version").value("AutoCAD 2018"))
                .andExpect(jsonPath("$.odaInstalled").value(false));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("passes through whatever the converter reported about itself")
        void passesThroughConverterDiagnostics() throws Exception {
            when(converter.convert(any(), anyString())).thenReturn(
                new ConverterService.ConvertResult(false, null, null,
                    mapper.readTree("""
                        {"version":"AutoCAD 2021","odaInstalled":true,"detail":"exit code 2"}""")));

            open(document("plan.dwg", "image/vnd.dwg", "AC1035"))
                .andExpect(jsonPath("$.type").value("dwg_binary"))
                .andExpect(jsonPath("$.version").value("AutoCAD 2021"))
                .andExpect(jsonPath("$.odaInstalled").value(true))
                .andExpect(jsonPath("$.detail").value("exit code 2"));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("still reports the retired converter as absent")
        void retiredConverterIsStillReported() throws Exception {
            // ADR 13 removed LibreDWG, so the converter stopped sending
            // this. Dropping a property from a released response is a
            // breaking change (§3.4), so it is filled in as false rather
            // than left out, and marked deprecated in the schema.
            when(converter.convert(any(), anyString())).thenReturn(
                new ConverterService.ConvertResult(false, null, null,
                    mapper.readTree("{\"version\":\"AutoCAD 2021\"}")));

            open(document("plan.dwg", "image/vnd.dwg", "AC1035"))
                .andExpect(jsonPath("$.libredwgInstalled").value(false));
        }
    }

    // ── PDFs ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("a PDF")
    class Pdfs {

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("comes back as a pointer to its bytes, not as the bytes")
        void pdfReturnsAPointer() throws Exception {
            // The viewer fetches the bytes separately through pdf.js, and
            // returning them here instead breaks it — it always expects a
            // JSON envelope from this endpoint.
            Document pdf = document("sheet.pdf", "application/pdf", "%PDF-1.7");

            open(pdf)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("pdf"))
                .andExpect(jsonPath("$.pdfUrl")
                    .value("/api/viewer/" + pdf.getId() + "/pdf?v=1"));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("carries its version in the URL so a stale copy is not served")
        void versionTravelsInTheUrl() throws Exception {
            // Processing replaces the bytes behind a fixed URL, so without
            // this the browser keeps showing the version before the
            // redaction.
            Document pdf = document("sheet.pdf", "application/pdf", "%PDF-1.7");
            pdf.setCurrentVersion(4);
            documentRepo.save(pdf);

            open(pdf)
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.pdfUrl")
                    .value("/api/viewer/" + pdf.getId() + "/pdf?v=4"));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("counts as version 1 when nothing has processed it yet")
        void unprocessedDocumentIsVersionOne() throws Exception {
            Document pdf = document("sheet.pdf", "application/pdf", "%PDF-1.7");
            pdf.setCurrentVersion(null);
            documentRepo.save(pdf);

            open(pdf).andExpect(jsonPath("$.version").value(1));
        }
    }

    // ── Everything else ───────────────────────────────────────────────────

    @Nested
    @DisplayName("other formats")
    class OtherFormats {

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("an SVG is returned as its own markup")
        void svgIsReturnedInline() throws Exception {
            open(document("detail.svg", "image/svg+xml", "<svg>on disk</svg>"))
                .andExpect(jsonPath("$.type").value("svg"))
                .andExpect(jsonPath("$.content").value("<svg>on disk</svg>"));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("an image comes back as bytes, not as JSON")
        void imageIsReturnedAsBytes() throws Exception {
            open(document("site.png", "image/png", "PNGDATA"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("a JPEG is served as image/jpeg, not as image/jpg")
        void jpgBecomesJpeg() throws Exception {
            // "image/jpg" is not a media type. Browsers mostly forgive it;
            // strict clients and content sniffing do not.
            open(document("photo.jpg", "", "JPEGDATA"))
                .andExpect(header().string("Content-Type", "image/jpeg"));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("a Revit file says it must be exported first")
        void revitIsNamed() throws Exception {
            // The most common 3D upload that cannot be opened, and the one
            // where a generic refusal sends somebody hunting for a bug.
            open(document("tower.rvt", "application/octet-stream", "RVT"))
                .andExpect(jsonPath("$.type").value("revit_binary"));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("a format nothing can open says so plainly")
        void unknownFormatIsNamed() throws Exception {
            open(document("archive.zip", "application/zip", "PK"))
                .andExpect(jsonPath("$.type").value("unsupported"))
                .andExpect(jsonPath("$.ext").value("zip"));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("a file with no extension at all is still answered")
        void extensionlessFileIsAnswered() throws Exception {
            open(document("README", "application/octet-stream", "text"))
                .andExpect(jsonPath("$.type").value("unsupported"))
                .andExpect(jsonPath("$.ext").value(""));
        }
    }

    // ── Documents that cannot be read ─────────────────────────────────────

    @Nested
    @DisplayName("a document the viewer cannot get at")
    class Unreadable {

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("one that does not exist is a 404")
        void unknownDocumentIsNotFound() throws Exception {
            mockMvc.perform(get("/api/viewer/{id}", 9_999_999L))
                .andExpect(status().isNotFound());
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("one with no stored file says so")
        void documentWithNoFileIsReported() throws Exception {
            Document detached = documentRepo.save(Document.builder()
                .name("No file").fileName("none.pdf").fileType("application/pdf")
                .filePath(null).documentType(Document.DocumentType.DRAWING)
                .project(project).uploadedBy(owner).build());

            open(detached)
                .andExpect(jsonPath("$.error")
                    .value(org.hamcrest.Matchers.containsString("No file path")));
        }

        @Test
        @WithMockUser(username = USERNAME, roles = "ENGINEER")
        @DisplayName("one whose file has gone from disk says that instead")
        void missingFileIsReported() throws Exception {
            // A different sentence from the one above, because they send
            // the reader to different people: one is a broken record, the
            // other is missing storage.
            Document pdf = document("sheet.pdf", "application/pdf", "%PDF-1.7");
            Files.delete(Path.of(pdf.getFilePath()));

            open(pdf)
                .andExpect(jsonPath("$.error")
                    .value(org.hamcrest.Matchers.containsString("not found on disk")));
        }
    }
}
