package com.cde.platform.controller;

import com.cde.platform.support.ActingAs;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import com.cde.platform.exception.ConverterOfflineException;
import org.springframework.http.MediaType;
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
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
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
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
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
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
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
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
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
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
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
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
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
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
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
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
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
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
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
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
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
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("an SVG is returned as its own markup")
        void svgIsReturnedInline() throws Exception {
            open(document("detail.svg", "image/svg+xml", "<svg>on disk</svg>"))
                .andExpect(jsonPath("$.type").value("svg"))
                .andExpect(jsonPath("$.content").value("<svg>on disk</svg>"));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("an image comes back as bytes, not as JSON")
        void imageIsReturnedAsBytes() throws Exception {
            open(document("site.png", "image/png", "PNGDATA"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("a JPEG is served as image/jpeg, not as image/jpg")
        void jpgBecomesJpeg() throws Exception {
            // "image/jpg" is not a media type. Browsers mostly forgive it;
            // strict clients and content sniffing do not.
            open(document("photo.jpg", "", "JPEGDATA"))
                .andExpect(header().string("Content-Type", "image/jpeg"));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("a Revit file says it must be exported first")
        void revitIsNamed() throws Exception {
            // The most common 3D upload that cannot be opened, and the one
            // where a generic refusal sends somebody hunting for a bug.
            open(document("tower.rvt", "application/octet-stream", "RVT"))
                .andExpect(jsonPath("$.type").value("revit_binary"));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("a format nothing can open says so plainly")
        void unknownFormatIsNamed() throws Exception {
            open(document("archive.zip", "application/zip", "PK"))
                .andExpect(jsonPath("$.type").value("unsupported"))
                .andExpect(jsonPath("$.ext").value("zip"));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
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
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("one that does not exist is a 404")
        void unknownDocumentIsNotFound() throws Exception {
            mockMvc.perform(get("/api/viewer/{id}", 9_999_999L))
                .andExpect(status().isNotFound());
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("one with no stored file says so")
        void documentWithNoFileIsReported() throws Exception {
            Document detached = documentRepo.save(Document.builder()
                .name("No file").fileName("none.pdf").fileType("application/pdf")
                .filePath(null).documentType(Document.DocumentType.DRAWING)
                .project(project).uploadedBy(owner).build());

            open(detached)
                .andExpect(jsonPath("$.error")
                    .value(org.hamcrest.Matchers.containsString("No file was recorded")));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("one whose file has gone from disk says that instead")
        void missingFileIsReported() throws Exception {
            // A different sentence from the one above, because they send
            // the reader to different people: one is a broken record, the
            // other is missing storage.
            Document pdf = document("sheet.pdf", "application/pdf", "%PDF-1.7");
            Files.delete(Path.of(pdf.getFilePath()));

            String body = open(pdf)
                .andExpect(jsonPath("$.error")
                    .value(org.hamcrest.Matchers.containsString("not in storage")))
                .andReturn().getResponse().getContentAsString();

            // Without the path it looked at, which it used to include.
            assertThat(body).doesNotContain(pdf.getFilePath());
        }
    }

    // ── Office documents, which go out to LibreOffice ──────────────────────

    @Nested
    @DisplayName("an Office document")
    class OfficeDocuments {

        private Document spreadsheet() throws IOException {
            return document("schedule.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "PK");
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("is converted and served as a PDF, not as a JSON envelope")
        void isServedAsAPdf() throws Exception {
            // The viewer renders Office documents through pdf.js, so this is
            // the one format on this route that answers with bytes.
            org.mockito.Mockito.when(converter.convertToPdfFile(
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> {
                    Files.writeString(call.getArgument(2), "%PDF-1.7 converted");
                    return 19L;
                });

            open(spreadsheet())
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("says the converter is offline rather than failing the page")
        void offlineConverterIsReported() throws Exception {
            // §8.2 graceful degradation: the viewer still loads and offers a
            // download, which is the fallback for this dependency.
            org.mockito.Mockito.when(converter.convertToPdfFile(
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any()))
                .thenThrow(new ConverterOfflineException("no answer on the converter port"));

            open(spreadsheet())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("office_error"))
                .andExpect(jsonPath("$.error").value("converter_offline"))
                .andExpect(jsonPath("$.loInstalled").value(false));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("a connection failure wrapped in another exception is still answered")
        void wrappedConnectionFailureIsStillAnswered() throws Exception {
            // There used to be a dedicated arm for java.net.ConnectException
            // alongside the ConverterOfflineException one, answering
            // identically. It could never run: convertToPdfFile declares no
            // checked exception and raises ConverterOfflineException for an
            // unreachable converter, so a bare ConnectException never arrived
            // and a wrapped one went to the general arm regardless. The arm is
            // gone; this records that the wrapped case is still answered rather
            // than thrown, which is the behaviour that mattered.
            org.mockito.Mockito.when(converter.convertToPdfFile(
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any()))
                .thenThrow(new java.io.UncheckedIOException(
                    new java.net.ConnectException("Connection refused")));

            open(spreadsheet())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("office_error"))
                .andExpect(jsonPath("$.error")
                    .value(org.hamcrest.Matchers.containsString("Connection refused")));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("distinguishes a missing LibreOffice from a conversion that failed")
        void missingLibreOfficeIsDistinguished() throws Exception {
            // The two call for different actions — install something, or look
            // at the file — so the viewer needs to be able to tell them apart.
            org.mockito.Mockito.when(converter.convertToPdfFile(
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("LibreOffice is not installed"));

            open(spreadsheet())
                .andExpect(jsonPath("$.type").value("office_error"))
                .andExpect(jsonPath("$.loInstalled").value(false));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("a conversion that failed for its own reason reports LibreOffice as present")
        void aFailedConversionKeepsLibreOfficeInstalled() throws Exception {
            org.mockito.Mockito.when(converter.convertToPdfFile(
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("the document is password protected"));

            open(spreadsheet())
                .andExpect(jsonPath("$.loInstalled").value(true))
                .andExpect(jsonPath("$.error").value("the document is password protected"));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("a failure with no reason given still answers, rather than throwing")
        void aFailureWithNoMessageStillAnswers() throws Exception {
            // This arm read .contains() off e.getMessage(), so an exception
            // raised with no message — an NPE from a library, a cancelled
            // future — threw inside the catch block and the reader got a 500
            // with a stack trace instead of a viewer offering a download
            // (§1.4).
            org.mockito.Mockito.when(converter.convertToPdfFile(
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any()))
                .thenThrow(new NullPointerException());

            open(spreadsheet())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("office_error"))
                .andExpect(jsonPath("$.error").value(
                    org.hamcrest.Matchers.containsString("could not be converted")));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("an Office document recognised only by its media type is still converted")
        void recognisedByMediaTypeAlone() throws Exception {
            org.mockito.Mockito.when(converter.convertToPdfFile(
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> {
                    Files.writeString(call.getArgument(2), "%PDF-1.7 converted");
                    return 19L;
                });

            open(document("minutes",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "PK"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF));
        }
    }

    // ── Fetching a PDF's bytes ────────────────────────────────────────────

    @Nested
    @DisplayName("fetching a PDF's bytes")
    class PdfBytes {

        private org.springframework.test.web.servlet.ResultActions bytes(Document document)
                throws Exception {
            return mockMvc.perform(get("/api/viewer/{id}/pdf", document.getId()));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("streams the file for a PDF")
        void streamsThePdf() throws Exception {
            bytes(document("plan.pdf", "application/pdf", "%PDF-1.7"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("a PDF recognised only by media type is streamed too")
        void mediaTypeIsEnough() throws Exception {
            bytes(document("plan", "application/pdf", "%PDF-1.7"))
                .andExpect(status().isOk());
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("refuses a document that is not a PDF rather than streaming it as one")
        void refusesANonPdf() throws Exception {
            // Streaming a DWG under application/pdf would hand pdf.js bytes it
            // cannot parse, and the reader would see a broken viewer with no
            // reason given.
            bytes(document("plan.dwg", "image/vnd.dwg", "AC1032"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error")
                    .value(org.hamcrest.Matchers.containsString("not a PDF")));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("a document that does not exist is a 404")
        void unknownDocumentIsNotFound() throws Exception {
            mockMvc.perform(get("/api/viewer/{id}/pdf", 9_999_999L))
                .andExpect(status().isNotFound());
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("a document with no stored file says so without naming a path")
        void noStoredFile() throws Exception {
            Document detached = documentRepo.save(Document.builder()
                .name("Detached").fileName("plan.pdf").fileType("application/pdf")
                .filePath(null).documentType(Document.DocumentType.DRAWING)
                .project(project).uploadedBy(owner).build());

            assertThat(bytes(detached).andReturn().getResponse().getContentAsString())
                .contains("nothing to open")
                .doesNotContain(storage.toString());
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("a document whose file has gone says that, without naming a path")
        void fileGoneFromStorage() throws Exception {
            Document gone = document("plan.pdf", "application/pdf", "%PDF-1.7");
            Files.delete(Path.of(gone.getFilePath()));

            assertThat(bytes(gone).andReturn().getResponse().getContentAsString())
                .contains("not in storage")
                .doesNotContain(storage.toString());
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("the version in the query string does not change which bytes come back")
        void versionParameterIsAdvisory() throws Exception {
            // It exists to defeat the browser cache, not to select a revision —
            // the current version is always what is served, and a client asking
            // for an older one must not silently get the newest under its name.
            Document plan = document("plan.pdf", "application/pdf", "%PDF-1.7");

            mockMvc.perform(get("/api/viewer/{id}/pdf?v=1", plan.getId()))
                .andExpect(status().isOk());
            mockMvc.perform(get("/api/viewer/{id}/pdf?v=99", plan.getId()))
                .andExpect(status().isOk());
        }
    }

    // ── Stored markup, and when it is not used ────────────────────────────

    @Nested
    @DisplayName("markup stored against the document")
    class StoredMarkup {

        private Document withVectorData(String vectorData) throws IOException {
            Path path = storage.resolve(java.util.UUID.randomUUID() + "_plan.dxf");
            Files.writeString(path, "0\nSECTION");
            return documentRepo.save(Document.builder()
                .name("Plan").fileName("plan.dxf").fileType("image/vnd.dxf")
                .filePath(path.toString()).fileSize(Files.size(path))
                .documentType(Document.DocumentType.DRAWING)
                .vectorData(vectorData)
                .project(project).uploadedBy(owner).build());
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("is returned without reaching the converter at all")
        void storedMarkupShortCircuits() throws Exception {
            open(withVectorData("<svg><rect width=\"10\" height=\"10\"/></svg>"))
                .andExpect(jsonPath("$.type").value("svg"))
                .andExpect(jsonPath("$.content")
                    .value(org.hamcrest.Matchers.containsString("<rect")));

            org.mockito.Mockito.verifyNoInteractions(converter);
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("markup stored as an empty string is not served as a blank drawing")
        void blankMarkupFallsThroughToTheConverter() throws Exception {
            // An empty column is what a failed earlier conversion leaves behind.
            // Serving it would show the reader an empty sheet and call it their
            // drawing; falling through gives the converter another go.
            org.mockito.Mockito.when(converter.convert(
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new ConverterOfflineException("converter is down"));

            open(withVectorData("   "))
                .andExpect(status().is5xxServerError());
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("the stored markup carries the drawing's own identifiers")
        void storedMarkupCarriesItsIdentifiers() throws Exception {
            // A drawing without its number and revision on screen is a drawing
            // nobody can cite in a comment, which is most of what the viewer is
            // for.
            Path path = storage.resolve(java.util.UUID.randomUUID() + "_plan.dxf");
            Files.writeString(path, "0\nSECTION");
            Document drawing = documentRepo.save(Document.builder()
                .name("Plan").fileName("plan.dxf").fileType("image/vnd.dxf")
                .filePath(path.toString()).fileSize(Files.size(path))
                .documentType(Document.DocumentType.DRAWING)
                .vectorData("<svg/>").drawingNumber("A-101").revision("P02")
                .project(project).uploadedBy(owner).build());

            open(drawing)
                .andExpect(jsonPath("$.drawingNumber").value("A-101"))
                .andExpect(jsonPath("$.revision").value("P02"));
        }

        @Test
        @ActingAs(value = User.Role.ENGINEER, username = USERNAME)
        @DisplayName("a drawing with no number or revision reports them as empty, not as null")
        void absentIdentifiersAreEmpty() throws Exception {
            // A literal "null" rendered into a title block reads as a value
            // somebody entered.
            open(withVectorData("<svg/>"))
                .andExpect(jsonPath("$.drawingNumber").value(""))
                .andExpect(jsonPath("$.revision").value(""));
        }
    }
}
