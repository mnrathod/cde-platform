package com.cde.platform.controller;

import com.cde.platform.dto.Dtos.*;
import com.cde.platform.model.*;
import com.cde.platform.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.util.*;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentRepository documentRepo;
    private final ProjectRepository projectRepo;
    private final UserRepository userRepo;

    @Value("${cde.storage.upload-dir}")
    private String uploadDir;

    public DocumentController(DocumentRepository documentRepo, ProjectRepository projectRepo,
                              UserRepository userRepo) {
        this.documentRepo = documentRepo;
        this.projectRepo = projectRepo;
        this.userRepo = userRepo;
    }

    @GetMapping("/project/{projectId}")
    public ResponseEntity<?> listByProject(
        @PathVariable Long projectId,
        @RequestParam(defaultValue = "0")  int page,
        @RequestParam(defaultValue = "50") int size,
        @RequestParam(defaultValue = "createdAt,desc") String sort
    ) {
        // Support both paginated and plain list responses
        if (page == 0 && size >= 50) {
            // Plain list for backwards compatibility with POC frontend
            var docs = documentRepo.findByProject_Id(projectId).stream().map(this::toResponse).toList();
            return ResponseEntity.ok(docs);
        }
        // Paginated response for Angular frontend
        var pageable = org.springframework.data.domain.PageRequest.of(
            page, size,
            sort.endsWith(",desc")
                ? org.springframework.data.domain.Sort.by(sort.split(",")[0]).descending()
                : org.springframework.data.domain.Sort.by(sort.split(",")[0]).ascending()
        );
        var docPage = documentRepo.findByProject_Id(projectId, pageable);
        return ResponseEntity.ok(new java.util.LinkedHashMap<String,Object>() {{
            put("content",       docPage.getContent().stream().map(DocumentController.this::toResponse).toList());
            put("totalElements", docPage.getTotalElements());
            put("totalPages",    docPage.getTotalPages());
            put("number",        docPage.getNumber());
            put("size",          docPage.getSize());
            put("first",         docPage.isFirst());
            put("last",          docPage.isLast());
        }});
    }

    @GetMapping("/{id}")
    public ResponseEntity<DocumentResponse> get(@PathVariable Long id) {
        return documentRepo.findById(id)
            .map(d -> ResponseEntity.ok(toResponse(d)))
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/upload")
    public ResponseEntity<?> upload(
        @RequestParam("projectId") Long projectId,
        @RequestParam("name") String name,
        @RequestParam(value = "description", required = false) String description,
        @RequestParam(value = "documentType", defaultValue = "DRAWING") String documentType,
        @RequestParam(value = "revision", required = false) String revision,
        @RequestParam(value = "drawingNumber", required = false) String drawingNumber,
        @RequestParam("file") MultipartFile file,
        @AuthenticationPrincipal UserDetails principal
    ) {
        var project = projectRepo.findById(projectId).orElse(null);
        if (project == null) return ResponseEntity.badRequest().body("Project not found");

        try {
            // Read bytes FIRST before transferTo() consumes the stream
            byte[] fileBytes = file.getBytes();

            Path dir = Paths.get(uploadDir, projectId.toString());
            Files.createDirectories(dir);
            String storedName = UUID.randomUUID() + "_" + file.getOriginalFilename();
            Path dest = dir.resolve(storedName);
            Files.write(dest, fileBytes);

            var uploader = userRepo.findByUsername(principal.getUsername()).orElseThrow();

            // Detect SVG by content or filename (browsers sometimes send octet-stream)
            String ct = file.getContentType();
            String origName = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
            boolean isSvg = (ct != null && (ct.contains("svg") || ct.contains("xml")))
                         || origName.endsWith(".svg");

            String vectorData = null;
            if (isSvg) {
                String content = new String(fileBytes, java.nio.charset.StandardCharsets.UTF_8);
                // Only store as vector if it actually contains SVG markup
                if (content.contains("<svg") || content.contains("<SVG")) {
                    vectorData = content;
                    ct = "image/svg+xml";
                }
            }

            // Detect file type from extension when browser sends octet-stream
            if (ct == null || ct.equals("application/octet-stream")) {
                ct = switch (origName.substring(origName.lastIndexOf('.')+1)) {
                    case "png"  -> "image/png";
                    case "jpg", "jpeg" -> "image/jpeg";
                    case "gif"  -> "image/gif";
                    case "pdf"  -> "application/pdf";
                    case "webp" -> "image/webp";
                    case "dxf"  -> "application/dxf";
                    case "dwg"  -> "application/dwg";
                    case "doc"  -> "application/msword";
                    case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                    case "xls"  -> "application/vnd.ms-excel";
                    case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                    case "ppt"  -> "application/vnd.ms-powerpoint";
                    case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
                    case "odt"  -> "application/vnd.oasis.opendocument.text";
                    case "ods"  -> "application/vnd.oasis.opendocument.spreadsheet";
                    case "odp"  -> "application/vnd.oasis.opendocument.presentation";
                    case "rtf"  -> "application/rtf";
                    case "txt"  -> "text/plain";
                    case "csv"  -> "text/csv";
                    case "ifc"  -> "application/x-step";
                    case "rvt"  -> "application/octet-stream";
                    case "rfa"  -> "application/octet-stream";
                    case "glb"  -> "model/gltf-binary";
                    case "gltf" -> "model/gltf+json";
                    case "obj"  -> "text/plain";
                    case "stl"  -> "application/octet-stream";
                    case "ply"  -> "application/octet-stream";
                    case "dae"  -> "model/vnd.collada+xml";
                    default     -> "application/octet-stream";
                };
            }

            var doc = Document.builder()
                .name(name)
                .description(description)
                .fileName(file.getOriginalFilename())
                .filePath(dest.toString())
                .fileType(ct)
                .fileSize((long) fileBytes.length)
                .documentType(Document.DocumentType.valueOf(documentType))
                .revision(revision)
                .drawingNumber(drawingNumber)
                .vectorData(vectorData)
                .project(project)
                .uploadedBy(uploader)
                .build();

            documentRepo.save(doc);
            return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(doc));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body("Upload failed: " + e.getMessage());
        }
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(
        @PathVariable Long id,
        @RequestParam String status
    ) {
        return documentRepo.findById(id).map(d -> {
            d.setStatus(Document.DocumentStatus.valueOf(status));
            documentRepo.save(d);
            return ResponseEntity.ok(toResponse(d));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!documentRepo.existsById(id)) return ResponseEntity.notFound().build();
        documentRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ── Chunked upload ────────────────────────────────────────────
    // In-memory chunk store keyed by uploadId
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.TreeMap<Integer,byte[]>> CHUNKS
        = new java.util.concurrent.ConcurrentHashMap<>();

    @PostMapping("/upload/chunk")
    public ResponseEntity<?> uploadChunk(
        @RequestParam("chunk")       MultipartFile chunk,
        @RequestParam("uploadId")    String uploadId,
        @RequestParam("chunkIndex")  int chunkIndex,
        @RequestParam("totalChunks") int totalChunks,
        @RequestParam("fileName")    String fileName,
        @RequestParam(value = "projectId",     required = false) Long projectId,
        @RequestParam(value = "name",          required = false) String name,
        @RequestParam(value = "documentType",  defaultValue = "DRAWING") String documentType,
        @RequestParam(value = "revision",      required = false) String revision,
        @RequestParam(value = "drawingNumber", required = false) String drawingNumber,
        @AuthenticationPrincipal org.springframework.security.core.userdetails.UserDetails principal
    ) {
        try {
            CHUNKS.computeIfAbsent(uploadId, k -> new java.util.TreeMap<>())
                  .put(chunkIndex, chunk.getBytes());

            int received = CHUNKS.get(uploadId).size();

            // All chunks received — assemble file
            if (received == totalChunks && projectId != null) {
                var project = projectRepo.findById(projectId).orElse(null);
                if (project == null) return ResponseEntity.badRequest().body("Project not found");

                // Assemble bytes in order
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                for (byte[] b : CHUNKS.remove(uploadId).values()) bos.write(b);
                byte[] fileBytes = bos.toByteArray();

                Path dir = Paths.get(uploadDir, projectId.toString());
                Files.createDirectories(dir);
                String storedName = java.util.UUID.randomUUID() + "_" + fileName;
                Files.write(dir.resolve(storedName), fileBytes);

                var uploader = userRepo.findByUsername(principal.getUsername()).orElseThrow();
                String ct = "application/octet-stream";
                try { ct = new org.apache.tika.Tika().detect(fileBytes, fileName); } catch (Exception ignored) {}

                var doc = Document.builder()
                    .name(name != null ? name : fileName.replaceAll("\\.[^.]+$",""))
                    .fileName(fileName).filePath(dir.resolve(storedName).toString())
                    .fileType(ct).fileSize((long) fileBytes.length)
                    .documentType(Document.DocumentType.valueOf(documentType))
                    .revision(revision).drawingNumber(drawingNumber)
                    .project(project).uploadedBy(uploader)
                    .status(Document.DocumentStatus.DRAFT)
                    .createdAt(java.time.LocalDateTime.now())
                    .build();

                return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                    .body(toResponse(documentRepo.save(doc)));
            }

            return ResponseEntity.ok(java.util.Map.of(
                "uploadId", uploadId, "received", received, "total", totalChunks));
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Chunk upload error: " + e.getMessage());
        }
    }


    private DocumentResponse toResponse(Document d) {
        return new DocumentResponse(
            d.getId(), d.getName(), d.getDescription(), d.getFileName(),
            d.getFileType(), d.getFileSize(), d.getDocumentType(), d.getStatus(),
            d.getRevision(), d.getDrawingNumber(), d.getSheetNumber(),
            d.getProject() != null ? d.getProject().getId() : null,
            d.getUploadedBy() != null ? d.getUploadedBy().getUsername() : null,
            d.getCreatedAt(), d.getUpdatedAt()
        );
    }
}
