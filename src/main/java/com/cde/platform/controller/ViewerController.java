package com.cde.platform.controller;

import com.cde.platform.dto.DiagnosticsDtos.ConverterStatusResponse;
import com.cde.platform.dto.ViewerDtos.ViewerPayload;
import com.cde.platform.openapi.ApiDocumentation;
import com.cde.platform.openapi.StandardErrorResponses;
import com.cde.platform.repository.DocumentRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.cde.platform.service.ConverterService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@RestController
@RequestMapping("/api/viewer")
@Tag(name = ApiDocumentation.TAG_VIEWER)
@StandardErrorResponses
public class ViewerController {

    private final DocumentRepository documentRepo;
    private final ConverterService converter;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final Set<String> OFFICE_MIME = Set.of(
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.oasis.opendocument.text",
        "application/vnd.oasis.opendocument.spreadsheet",
        "application/vnd.oasis.opendocument.presentation",
        "application/rtf","text/rtf","text/plain","text/csv","text/html"
    );
    private static final Set<String> OFFICE_EXT = Set.of(
        "doc","docx","xls","xlsx","ppt","pptx",
        "odt","ods","odp","rtf","txt","csv","html","htm"
    );
    private static final Set<String> MODEL3D_EXT = Set.of(
        "ifc","glb","gltf","obj","stl","ply","dae","3ds","rvt","rfa"
    );

    public ViewerController(DocumentRepository documentRepo, ConverterService converter) {
        this.documentRepo = documentRepo;
        this.converter = converter;
    }

    @Operation(
        operationId = "getViewerPayload",
        summary = "Get what the viewer needs to open a document",
        description = """
            Returns whatever suits the document's format: SVG markup for a drawing, a pointer to \
            the bytes for a PDF, extracted geometry for a model, or an explanation of why it \
            cannot be opened. Read the `type` member first — it decides the shape of the rest.

            Images and mesh formats (GLB, STL, OBJ, PLY, DAE) are returned as raw bytes with the \
            matching content type rather than as JSON, so check the response's content type \
            before parsing it. Office documents are converted and returned as PDF bytes.

            **A `200` does not mean the document opened.** The variants that report a problem — \
            `error`, `dwg_binary`, `office_error`, `revit_binary`, `3d_error`, `unsupported` — \
            are also returned with `200`. This is a defect kept for compatibility: every client \
            branches on `type`, so correcting the status is a coordinated change on both sides.

            Requires the `document:read` permission.""")
    @ApiResponse(responseCode = "200",
        description = "What the viewer needs, or a described reason it cannot open the document.",
        content = {
            @Content(mediaType = "application/json",
                     schema = @Schema(implementation = ViewerPayload.class)),
            @Content(mediaType = "application/pdf",
                     schema = @Schema(type = "string", format = "binary")),
            @Content(mediaType = "image/*", schema = @Schema(type = "string", format = "binary")),
            @Content(mediaType = "model/gltf-binary",
                     schema = @Schema(type = "string", format = "binary"))
        })
    @ApiResponse(responseCode = "404",
        description = "No document with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @GetMapping("/{documentId}")
    public ResponseEntity<?> getViewerData(
        @Parameter(description = "Identifier of the document to open.", example = "1180")
        @PathVariable Long documentId
    ) {
        var docOpt = documentRepo.findById(documentId);
        if (docOpt.isEmpty()) return ResponseEntity.notFound().build();
        var doc = docOpt.get();

        // 1. Inline SVG
        if (doc.getVectorData() != null && !doc.getVectorData().isBlank()) {
            return ResponseEntity.ok(Map.of(
                "type","svg","content",doc.getVectorData(),
                "name",doc.getName(),
                "drawingNumber",s(doc.getDrawingNumber()),
                "revision",s(doc.getRevision())));
        }

        if (doc.getFilePath() == null)
            return err("No file path stored for this document.");

        Path path = Paths.get(doc.getFilePath());
        if (!Files.exists(path))
            return err("File not found on disk: " + path);

        String ct  = s(doc.getFileType()).toLowerCase();
        String name = s(doc.getFileName()).toLowerCase();
        String ext  = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";

        try {
            // ── CAD ────────────────────────────────────────────
            if (ext.equals("dxf") || ext.equals("dwg") || ct.contains("dxf") || ct.contains("dwg")) {
                var result = converter.convert(path, doc.getFileName());
                if (result.success()) {
                    String by = result.rawJson() != null
                        ? result.rawJson().path("convertedBy").asText("ezdxf") : "java-fallback";
                    return ResponseEntity.ok(Map.of(
                        "type","svg","content",result.svg(),
                        "name",doc.getName(),
                        "drawingNumber",s(doc.getDrawingNumber()),
                        "revision",s(doc.getRevision()),
                        "renderedBy",by));
                }
                // DWG error — build rich response
                Map<String,Object> resp = new LinkedHashMap<>();
                resp.put("type","dwg_binary");
                resp.put("name",doc.getName());
                resp.put("fileName",s(doc.getFileName()));
                if (result.rawJson() != null) {
                    result.rawJson().fields().forEachRemaining(e ->
                        resp.put(e.getKey(), e.getValue().isBoolean()
                            ? e.getValue().asBoolean() : e.getValue().asText()));
                } else {
                    resp.put("version", s(result.error()).replace("DWG_BINARY:",""));
                    resp.put("odaInstalled", false);
                    resp.put("libredwgInstalled", false);
                }
                return ResponseEntity.ok(resp);
            }

            // ── PDF ─────────────────────────────────────────────
            // Return JSON metadata here; the Angular viewer fetches the
            // actual bytes separately from /{documentId}/pdf (below) via
            // pdf.js. Returning raw bytes from this endpoint breaks the
            // frontend, which always expects a JSON envelope from it.
            if (ext.equals("pdf") || ct.contains("pdf")) {
                // Processing operations advance the document to a new version
                // in place, so the bytes behind a fixed URL change over time.
                // The version travels in the URL to keep the browser and
                // pdf.js from serving a cached copy of the previous one.
                int version = doc.getCurrentVersion() != null ? doc.getCurrentVersion() : 1;
                return ResponseEntity.ok(Map.of(
                    "type","pdf","name",doc.getName(),
                    "fileName",s(doc.getFileName()),
                    "drawingNumber",s(doc.getDrawingNumber()),
                    "revision",s(doc.getRevision()),
                    "version",version,
                    "pdfUrl","/api/viewer/" + documentId + "/pdf?v=" + version));
            }

            // ── Office -> PDF via LibreOffice ───────────────────
            if (OFFICE_EXT.contains(ext) || OFFICE_MIME.contains(ct)) {
                try {
                    byte[] pdf = converter.convertToPdf(path, ct);
                    return servePdf(pdf, doc.getName() + ".pdf");
                } catch (java.net.ConnectException e) {
                    return ResponseEntity.ok(Map.of(
                        "type","office_error","name",doc.getName(),
                        "fileName",s(doc.getFileName()),
                        "error","converter_offline","loInstalled",false));
                } catch (Exception e) {
                    return ResponseEntity.ok(Map.of(
                        "type","office_error","name",doc.getName(),
                        "fileName",s(doc.getFileName()),
                        "error",e.getMessage(),
                        "loInstalled",!e.getMessage().contains("not installed")));
                }
            }

            // ── 3D Models (IFC, GLB, OBJ, STL, Revit) ───────────────
            if (MODEL3D_EXT.contains(ext) || ct.contains("ifc") || ct.contains("step")) {
                return handle3D(doc, path, ct);
            }

            // ── SVG ─────────────────────────────────────────────
            if (ext.equals("svg") || ct.contains("svg")) {
                return ResponseEntity.ok(Map.of(
                    "type","svg","content",Files.readString(path),
                    "name",doc.getName(),
                    "drawingNumber",s(doc.getDrawingNumber()),
                    "revision",s(doc.getRevision())));
            }

            // ── Images ──────────────────────────────────────────
            if (ct.startsWith("image/") || Set.of("png","jpg","jpeg","gif","webp","bmp").contains(ext)) {
                byte[] bytes = Files.readAllBytes(path);
                String mime = ct.startsWith("image/") ? ct : "image/" + (ext.equals("jpg") ? "jpeg" : ext);
                return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(mime))
                    .contentLength(bytes.length)
                    .body(bytes);
            }

            // ── Unknown ─────────────────────────────────────────
            return ResponseEntity.ok(Map.of(
                "type","unsupported","name",doc.getName(),
                "fileName",s(doc.getFileName()),"ext",ext));

        } catch (IOException e) {
            return err("Read error: " + e.getMessage());
        }
    }

    // ── Serve raw PDF bytes for pdf.js (called by the Angular viewer using
    //    the pdfUrl returned from /{documentId} above) ────────────────────
    @Operation(
        operationId = "getPdfBytes",
        summary = "Stream a PDF's bytes",
        description = """
            The bytes of the document's current version, served inline for the viewer to render.

            Not cached: processing replaces the bytes behind this URL, so a cached response would \
            show a version that no longer exists. The viewer passes the version in the query \
            string for the same reason.

            Requires the `document:read` permission.""")
    @ApiResponse(responseCode = "200", description = "The PDF's bytes.",
        content = @Content(mediaType = "application/pdf",
                           schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "404",
        description = "No such document, or its file is missing from storage.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "422",
        description = "The document is not a PDF.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @GetMapping("/{documentId}/pdf")
    public ResponseEntity<?> getPdfBytes(
        @Parameter(description = "Identifier of the document.", example = "1180")
        @PathVariable Long documentId,
        @Parameter(description = "Version the client believes it is fetching. Present so a "
                               + "browser does not serve a superseded copy from cache; the "
                               + "current version is always what is returned.",
                   example = "3")
        @RequestParam(value = "v", required = false) Integer v
    ) {
        var docOpt = documentRepo.findById(documentId);
        if (docOpt.isEmpty()) return ResponseEntity.notFound().build();
        var doc = docOpt.get();

        if (doc.getFilePath() == null)
            return err("No file path stored for this document.");

        Path path = Paths.get(doc.getFilePath());
        if (!Files.exists(path))
            return err("File not found on disk: " + path);

        String ct   = s(doc.getFileType()).toLowerCase();
        String name = s(doc.getFileName()).toLowerCase();
        String ext  = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";

        if (!ext.equals("pdf") && !ct.contains("pdf"))
            return ResponseEntity.badRequest().body(Map.of("error","Document is not a PDF"));

        try {
            return servePdf(Files.readAllBytes(path), s(doc.getFileName()));
        } catch (IOException e) {
            return err("Read error: " + e.getMessage());
        }
    }

    @Operation(
        operationId = "getConverterStatus",
        summary = "Check whether the conversion service is reachable",
        description = """
            The viewer asks before offering conversion-dependent actions, so it can say why \
            something is unavailable rather than failing when someone tries it.

            Requires authentication.""")
    @ApiResponse(responseCode = "200", description = "Whether the conversion service answered.")
    @GetMapping("/converter-status")
    public ResponseEntity<ConverterStatusResponse> converterStatus() {
        return ResponseEntity.ok(new ConverterStatusResponse(converter.isConverterRunning()));
    }

    private ResponseEntity<byte[]> servePdf(byte[] bytes, String filename) {
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .contentLength(bytes.length)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
            // The bytes at this URL are replaced whenever a new version is
            // committed, so a cached response would show a stale document.
            .header(HttpHeaders.CACHE_CONTROL, "no-cache, must-revalidate")
            .header("X-Source-Type", "pdf")
            .body(bytes);
    }

    // ── 3D handler ────────────────────────────────────────────────
    private ResponseEntity<?> handle3D(com.cde.platform.model.Document doc, Path path, String ct) {
        String ext = path.getFileName().toString().toLowerCase();
        ext = ext.contains(".") ? ext.substring(ext.lastIndexOf('.')+1) : "";

        // Revit binary — can't open without Revit
        if (ext.equals("rvt") || ext.equals("rfa")) {
            return ResponseEntity.ok(Map.of(
                "type", "revit_binary",
                "name", doc.getName(),
                "fileName", s(doc.getFileName())));
        }

        try {
            // POST to converter
            String converterUrl = "http://localhost:5001";
            var body = mapper.createObjectNode();
            body.put("path", path.toAbsolutePath().toString());
            body.put("contentType", ct);

            java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5)).build();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(converterUrl + "/convert"))
                .timeout(java.time.Duration.ofSeconds(180))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

            // GLB/STL/OBJ — raw bytes response
            if (Set.of("glb","gltf","obj","stl","ply","dae").contains(ext)) {
                java.net.http.HttpResponse<byte[]> resp =
                    http.send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                String respCt = resp.headers().firstValue("Content-Type").orElse("application/octet-stream");
                return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(respCt))
                    .contentLength(resp.body().length)
                    .header("X-3D-Format", ext)
                    .header("Access-Control-Expose-Headers", "X-3D-Format")
                    .body(resp.body());
            }

            // IFC — JSON with geometry data
            java.net.http.HttpResponse<String> resp =
                http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            com.fasterxml.jackson.databind.JsonNode json = mapper.readTree(resp.body());
            if (!json.path("success").asBoolean(false)) {
                return ResponseEntity.ok(Map.of(
                    "type", "error",
                    "error", json.path("error").asText("IFC conversion failed")));
            }
            return ResponseEntity.ok(json);

        } catch (java.net.ConnectException e) {
            return ResponseEntity.ok(Map.of(
                "type","3d_error","name",doc.getName(),"fileName",s(doc.getFileName()),
                "error","converter_offline"));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                "type","error","error","3D conversion error: " + e.getMessage()));
        }
    }

    private ResponseEntity<?> err(String msg) {
        return ResponseEntity.ok(Map.of("type","error","error",msg));
    }

    private String s(String v) { return v != null ? v : ""; }
}
