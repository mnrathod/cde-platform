package com.cde.platform.controller;

import com.cde.platform.repository.DocumentRepository;
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

    @GetMapping("/{documentId}")
    public ResponseEntity<?> getViewerData(@PathVariable Long documentId) {
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
                return ResponseEntity.ok(Map.of(
                    "type","pdf","name",doc.getName(),
                    "fileName",s(doc.getFileName()),
                    "drawingNumber",s(doc.getDrawingNumber()),
                    "revision",s(doc.getRevision()),
                    "pdfUrl","/api/viewer/" + documentId + "/pdf"));
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
    @GetMapping("/{documentId}/pdf")
    public ResponseEntity<?> getPdfBytes(@PathVariable Long documentId) {
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

    @GetMapping("/converter-status")
    public ResponseEntity<?> converterStatus() {
        return ResponseEntity.ok(Map.of("running", converter.isConverterRunning()));
    }

    private ResponseEntity<byte[]> servePdf(byte[] bytes, String filename) {
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .contentLength(bytes.length)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
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
