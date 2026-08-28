package com.cde.platform.controller;

import com.cde.platform.dto.ViewerDtos.ModelTreeNode;
import com.cde.platform.dto.ViewerDtos.ViewerPayload;
import com.cde.platform.openapi.ApiDocumentation;
import com.cde.platform.openapi.StandardErrorResponses;
import com.cde.platform.repository.DocumentRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
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
@Tag(name = ApiDocumentation.TAG_VIEWER_3D)
@StandardErrorResponses
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

    @Operation(
        operationId = "getModel",
        summary = "Get a model's geometry",
        description = """
            IFC is extracted by the conversion service and returned as JSON. Mesh formats — GLB, \
            glTF, OBJ, STL, PLY, DAE — are streamed as raw bytes with the matching content type \
            and an `X-3D-Format` header, so check the content type before parsing. Revit files \
            cannot be opened at all and report why.

            As with the document viewer, a problem is reported with `200` and a described \
            payload rather than an error status. Read `success` and `type`.

            The structured hierarchy from `/tree` is the primary route to a model's information; \
            this endpoint serves the visual layer over it.

            Requires the `document:read` permission.""")
    @ApiResponse(responseCode = "200",
        description = "The geometry, or a described reason it is unavailable.",
        content = {
            @Content(mediaType = "application/json",
                     schema = @Schema(implementation = ViewerPayload.class)),
            @Content(mediaType = "model/gltf-binary",
                     schema = @Schema(type = "string", format = "binary"))
        })
    @ApiResponse(responseCode = "404",
        description = "No document with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @GetMapping("/{documentId}")
    public ResponseEntity<?> get3DModel(
        @Parameter(description = "Identifier of the model document.", example = "1212")
        @PathVariable Long documentId
    ) {
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
    @Operation(
        operationId = "getModelTree",
        summary = "Get a model's hierarchy as a navigable tree",
        description = """
            The structured route to everything the model holds — the interface a keyboard or a \
            screen reader uses, and the one a rendered canvas cannot replace. It supports the \
            same navigation and selection the visual view does.

            **When the conversion service cannot be reached, a placeholder outline is returned \
            instead of the model's real hierarchy**, and its root carries `synthetic: true`. It \
            lists the element classes a model of this kind usually contains, not this model's \
            contents. A client must not present it as the model's structure; check the flag.

            Requires the `document:read` permission.""")
    @ApiResponse(responseCode = "200",
        description = "The hierarchy, rooted at the model. Check the root's `synthetic` flag "
                    + "before treating it as the model's real structure.",
        content = @Content(mediaType = "application/json",
                           array = @ArraySchema(schema = @Schema(implementation = ModelTreeNode.class))))
    @ApiResponse(responseCode = "404",
        description = "No document with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @GetMapping("/{documentId}/tree")
    public ResponseEntity<?> getIfcTree(
        @Parameter(description = "Identifier of the model document.", example = "1212")
        @PathVariable Long documentId
    ) {
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

    /**
     * A placeholder outline, used when the model's own hierarchy cannot be
     * extracted.
     *
     * <p>The root is marked {@code synthetic} so a client can tell this apart
     * from the real thing. Without the marker it is indistinguishable from an
     * extracted hierarchy, which makes it worse than returning nothing: a
     * reader navigating the tree would be reading invented element classes as
     * though they were the building's contents.
     */
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
        root.put("synthetic", true);
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
