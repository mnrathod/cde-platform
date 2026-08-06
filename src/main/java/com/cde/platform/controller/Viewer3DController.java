package com.cde.platform.controller;

import com.cde.platform.repository.DocumentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

@RestController
@RequestMapping("/api/viewer3d")
public class Viewer3DController {

    private static final Set<String> EXT_3D = Set.of(
        "ifc","glb","gltf","obj","stl","ply","dae","3ds","rvt","rfa"
    );

    private final DocumentRepository documentRepo;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();
    private static final String CONVERTER = "http://localhost:5001";

    public Viewer3DController(DocumentRepository documentRepo) {
        this.documentRepo = documentRepo;
    }

    @GetMapping("/{documentId}")
    public ResponseEntity<?> get3DModel(@PathVariable Long documentId) {
        var docOpt = documentRepo.findById(documentId);
        if (docOpt.isEmpty()) return ResponseEntity.notFound().build();
        var doc = docOpt.get();

        if (doc.getFilePath() == null)
            return ResponseEntity.ok(Map.of("success", false, "error", "No file path stored."));

        Path path = Paths.get(doc.getFilePath());
        if (!Files.exists(path))
            return ResponseEntity.ok(Map.of("success", false, "error", "File not found: " + path));

        String name = doc.getFileName() != null ? doc.getFileName().toLowerCase() : "";
        String ext  = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
        String ct   = doc.getFileType() != null ? doc.getFileType().toLowerCase() : "";

        if (ext.equals("rvt") || ext.equals("rfa")) {
            return ResponseEntity.ok(Map.of(
                "type", "revit_binary",
                "name", doc.getName(),
                "fileName", name));
        }

        if (Set.of("glb","gltf","obj","stl","ply","dae").contains(ext)) {
            return serveBinary(path, ext);
        }

        if (ext.equals("ifc") || ct.contains("ifc") || ct.contains("step")) {
            return convertIFC(path, doc);
        }

        return ResponseEntity.ok(Map.of(
            "success", false,
            "error", "Unsupported 3D format: ." + ext));
    }


    // ── IFC Model Tree ────────────────────────────────────────────
    @GetMapping("/{documentId}/tree")
    public ResponseEntity<?> getIfcTree(@PathVariable Long documentId) {
        var docOpt = documentRepo.findById(documentId);
        if (docOpt.isEmpty()) return ResponseEntity.notFound().build();
        var doc = docOpt.get();

        if (doc.getFilePath() == null) return ResponseEntity.ok(java.util.List.of());

        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("path", Paths.get(doc.getFilePath()).toAbsolutePath().toString());
            body.put("contentType", "application/ifc");
            body.put("action", "tree");

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(CONVERTER + "/ifc-tree"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode json = mapper.readTree(resp.body());
            return ResponseEntity.ok(json);

        } catch (java.net.ConnectException e) {
            // Converter offline — return synthetic tree from metadata
            return ResponseEntity.ok(buildSyntheticTree(doc));
        } catch (Exception e) {
            return ResponseEntity.ok(buildSyntheticTree(doc));
        }
    }

    private java.util.List<java.util.Map<String,Object>> buildSyntheticTree(
        com.cde.platform.model.Document doc
    ) {
        var types = java.util.List.of(
            "IfcWall","IfcSlab","IfcColumn","IfcBeam","IfcDoor",
            "IfcWindow","IfcStair","IfcRoof","IfcFurnishingElement","IfcSpace"
        );
        var children = types.stream().map(t -> {
            var node = new java.util.LinkedHashMap<String,Object>();
            node.put("id", t);
            node.put("name", t.replace("Ifc",""));
            node.put("type", t);
            node.put("expanded", false);
            node.put("selected", false);
            node.put("visible", true);
            node.put("children", java.util.List.of());
            return node;
        }).toList();

        var root = new java.util.LinkedHashMap<String,Object>();
        root.put("id", "root");
        root.put("name", doc.getName() != null ? doc.getName() : "Building Model");
        root.put("type", "IfcBuilding");
        root.put("expanded", true);
        root.put("selected", false);
        root.put("visible", true);
        root.put("children", children);
        return java.util.List.of(root);
    }

    private ResponseEntity<?> convertIFC(Path path, com.cde.platform.model.Document doc) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("path", path.toAbsolutePath().toString());
            body.put("contentType", "application/ifc");

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(CONVERTER + "/convert"))
                .timeout(Duration.ofSeconds(180))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode json = mapper.readTree(resp.body());

            if (!json.path("success").asBoolean(false)) {
                return ResponseEntity.ok(Map.of(
                    "success", false,
                    "error", json.path("error").asText("IFC conversion failed")));
            }

            ((ObjectNode) json).put("docName", doc.getName());
            return ResponseEntity.ok(json);

        } catch (java.net.ConnectException e) {
            return ResponseEntity.ok(Map.of(
                "success", false,
                "error", "converter_offline",
                "detail", "Start the converter: python converter/app.py"));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private ResponseEntity<byte[]> serveBinary(Path path, String ext) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            String mime = switch (ext) {
                case "glb"  -> "model/gltf-binary";
                case "gltf" -> "model/gltf+json";
                case "obj"  -> "text/plain";
                case "dae"  -> "text/xml";
                default     -> "application/octet-stream";
            };
            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mime))
                .contentLength(bytes.length)
                .header("X-3D-Format", ext)
                .header("Access-Control-Expose-Headers", "X-3D-Format")
                .body(bytes);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(null);
        }
    }
}
