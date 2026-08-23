package com.cde.platform.controller;

import com.cde.platform.dto.DocumentDtos.*;
import com.cde.platform.dto.PageResponse;
import com.cde.platform.exception.ApiProblem;
import com.cde.platform.exception.ResourceNotFoundException;
import com.cde.platform.model.*;
import com.cde.platform.openapi.ApiDocumentation;
import com.cde.platform.openapi.StandardErrorResponses;
import com.cde.platform.service.DocumentDeletionService;
import com.cde.platform.repository.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
@Tag(name = ApiDocumentation.TAG_DOCUMENTS)
@StandardErrorResponses
public class DocumentController {

    /**
     * Columns a listing may be sorted by.
     *
     * <p>An allow-list rather than a pass-through: the value reaches a
     * {@code Sort}, which becomes an {@code ORDER BY}, and anything a caller
     * can put in a query string is something a caller chose.
     */
    private static final Set<String> SORTABLE_FIELDS =
        Set.of("createdAt", "updatedAt", "name", "fileSize", "revision", "drawingNumber");

    private static final String DEFAULT_SORT = "createdAt,desc";

    private final DocumentRepository documentRepo;
    private final ProjectRepository projectRepo;
    private final UserRepository userRepo;
    private final DocumentDeletionService deletionService;

    @Value("${cde.storage.upload-dir}")
    private String uploadDir;

    public DocumentController(DocumentRepository documentRepo, ProjectRepository projectRepo,
                              UserRepository userRepo, DocumentDeletionService deletionService) {
        this.documentRepo = documentRepo;
        this.projectRepo = projectRepo;
        this.userRepo = userRepo;
        this.deletionService = deletionService;
    }

    @Operation(
        operationId = "listDocumentsByProject",
        summary = "List the documents in a project",
        description = """
            Always returns a page envelope, including when the page holds everything. An endpoint \
            that returned a bare array in some circumstances and an envelope in others could not \
            be described by one schema, and a client had to work out which it had received by \
            inspecting what it had just parsed.

            Sortable by `createdAt`, `updatedAt`, `name`, `fileSize`, `revision` and \
            `drawingNumber`. Any other field is rejected rather than ignored, so a misspelled \
            sort does not quietly return an arbitrary order.

            Requires the `document:read` permission.""")
    @ApiResponse(responseCode = "200", description = "One page of the project's documents.")
    @ApiResponse(responseCode = "404",
        description = "No project with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @GetMapping("/project/{projectId}")
    public PageResponse<DocumentResponse> listByProject(
        @Parameter(description = "Identifier of the project whose documents to list.", example = "42")
        @PathVariable Long projectId,

        @Parameter(description = "Zero-based page index.", example = "0",
                   schema = @Schema(type = "integer", format = "int32", defaultValue = "0", minimum = "0"))
        @RequestParam(defaultValue = "0") int page,

        @Parameter(description = "Maximum documents in the page.", example = "50",
                   schema = @Schema(type = "integer", format = "int32", defaultValue = "50",
                                    minimum = "1", maximum = "200"))
        @RequestParam(defaultValue = "50") int size,

        @Parameter(description = "Field and direction, as `field,asc` or `field,desc`.",
                   example = "createdAt,desc",
                   schema = @Schema(type = "string", defaultValue = DEFAULT_SORT,
                                    pattern = "^[a-zA-Z]+(,(asc|desc))?$"))
        @RequestParam(defaultValue = DEFAULT_SORT) String sort
    ) {
        if (!projectRepo.existsById(projectId)) {
            throw new ResourceNotFoundException("No such project.");
        }

        var pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 200), parseSort(sort));

        return PageResponse.from(documentRepo.findByProject_Id(projectId, pageable), this::toResponse);
    }

    /**
     * Turns {@code field,direction} into a {@code Sort}, refusing any field not
     * on the allow-list.
     *
     * @throws IllegalArgumentException if the field is not sortable
     */
    private Sort parseSort(String sort) {
        String[] parts = sort.split(",", 2);
        String field = parts[0].trim();

        if (!SORTABLE_FIELDS.contains(field)) {
            throw new IllegalArgumentException("Not a sortable field: " + field);
        }
        boolean descending = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim());
        return descending ? Sort.by(field).descending() : Sort.by(field).ascending();
    }

    @Operation(
        operationId = "getDocument",
        summary = "Read one document's metadata",
        description = """
            Metadata only — the file itself comes from the viewer or version endpoints, so a \
            listing never carries content.

            Requires the `document:read` permission.""")
    @ApiResponse(responseCode = "200", description = "The document's metadata.")
    @ApiResponse(responseCode = "404",
        description = "No document with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @GetMapping("/{id}")
    public ResponseEntity<DocumentResponse> get(
        @Parameter(description = "Identifier of the document.", example = "1180")
        @PathVariable Long id
    ) {
        return documentRepo.findById(id)
            .map(d -> ResponseEntity.ok(toResponse(d)))
            .orElse(ResponseEntity.notFound().build());
    }

    @Operation(
        operationId = "uploadDocument",
        summary = "Upload a document in one request",
        description = """
            Multipart upload for a file small enough to send in a single request. For a large file \
            use the chunked route, which survives an interrupted connection.

            The stored name is generated server-side; the name the client sent is kept as metadata \
            for display and for the download header only, and is never used as a path. The media \
            type is detected from the file's own content rather than from its extension or the \
            browser's claim.

            Requires the `document:write` permission on the target project.""")
    @ApiResponse(responseCode = "201", description = "The document as created.",
        content = @Content(mediaType = "application/json",
                           schema = @Schema(implementation = DocumentResponse.class)))
    @ApiResponse(responseCode = "404",
        description = "No project with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "413",
        description = "The file exceeds the per-file limit or the tenant's storage quota.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "422",
        description = "A required part is missing, or `documentType` is not a recognised value.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PostMapping("/upload")
    public ResponseEntity<?> upload(
        @Parameter(description = "Project to file the document under.", example = "42")
        @RequestParam("projectId") Long projectId,

        @Parameter(description = "Display name, independent of the file name.",
                   example = "GA Plan — Level 02")
        @RequestParam("name") String name,

        @Parameter(description = "Free text about this document.", example = "Issued for coordination.")
        @RequestParam(value = "description", required = false) String description,

        @Parameter(description = "What kind of document this is.", example = "DRAWING")
        @RequestParam(value = "documentType", defaultValue = "DRAWING") String documentType,

        @Parameter(description = "Revision identifier as the originator issued it.", example = "P02.1")
        @RequestParam(value = "revision", required = false) String revision,

        @Parameter(description = "Drawing number from the title block.", example = "RVD-XX-02-DR-A-1200")
        @RequestParam(value = "drawingNumber", required = false) String drawingNumber,

        @Parameter(description = "The file itself.")
        @RequestParam("file") MultipartFile file,

        @Parameter(hidden = true) @AuthenticationPrincipal UserDetails principal,
        HttpServletRequest httpRequest
    ) {
        var project = projectRepo.findById(projectId)
            .orElseThrow(() -> new ResourceNotFoundException("No such project."));

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
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiProblem.of(
                HttpStatus.INTERNAL_SERVER_ERROR, "storage-write-failed", "Upload failed",
                "The file could not be stored. Try again; if it keeps failing, quote the trace id.",
                httpRequest));
        }
    }

    @Operation(
        operationId = "updateDocumentStatus",
        summary = "Move a document to another review status",
        description = """
            This is the document's own review state, and is distinct from the ISO 19650 container \
            state, which changes only through the container state machine.

            Requires the `document:write` permission.""")
    @ApiResponse(responseCode = "200", description = "The document as it now stands.")
    @ApiResponse(responseCode = "404",
        description = "No document with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PatchMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(
        @Parameter(description = "Identifier of the document.", example = "1180")
        @PathVariable Long id,

        @Parameter(description = "Status to move the document to.", example = "IN_REVIEW",
                   schema = @Schema(implementation = Document.DocumentStatus.class))
        @RequestParam String status
    ) {
        return documentRepo.findById(id).map(d -> {
            d.setStatus(Document.DocumentStatus.valueOf(status));
            documentRepo.save(d);
            return ResponseEntity.ok(toResponse(d));
        }).orElse(ResponseEntity.notFound().build());
    }

    @Operation(
        operationId = "deleteDocument",
        summary = "Delete a document and everything attached to it",
        description = """
            Cascades through the document's annotations, replies, versions and signatures, which \
            reference it with non-null foreign keys. This is not reversible, and it removes the \
            signed record along with the document.

            Requires the `document:write` permission.""")
    @ApiResponse(responseCode = "204", description = "The document and its dependents are gone.")
    @ApiResponse(responseCode = "404",
        description = "No document with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
        @Parameter(description = "Identifier of the document.", example = "1180")
        @PathVariable Long id
    ) {
        // Delegated: annotations, replies and signatures reference the
        // document with non-null, non-cascading foreign keys, so deleting it
        // directly fails on a referential-integrity constraint.
        return deletionService.deleteDocument(id)
            ? ResponseEntity.noContent().build()
            : ResponseEntity.notFound().build();
    }

    // ── Chunked upload ────────────────────────────────────────────
    // In-memory chunk store keyed by uploadId
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.TreeMap<Integer,byte[]>> CHUNKS
        = new java.util.concurrent.ConcurrentHashMap<>();

    @Operation(
        operationId = "uploadDocumentChunk",
        summary = "Send one chunk of a large upload",
        description = """
            Chunks may arrive in any order. Each is acknowledged with a count of what has arrived \
            so far; the chunk that completes the set assembles the file and returns the created \
            document instead, so a client tells completion from progress by the shape of the reply.

            The upload identifier is chosen by the client and must be unique to this upload — \
            reusing one belonging to an upload still in flight would interleave the two.

            Requires the `document:write` permission on the target project.""")
    @ApiResponse(responseCode = "200", description = "The chunk was stored; more are expected.",
        content = @Content(mediaType = "application/json",
                           schema = @Schema(implementation = ChunkAccepted.class)))
    @ApiResponse(responseCode = "201",
        description = "That was the last chunk. The file was assembled and the document created.",
        content = @Content(mediaType = "application/json",
                           schema = @Schema(implementation = DocumentResponse.class)))
    @ApiResponse(responseCode = "404",
        description = "No project with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "422",
        description = "A required part is missing, or the chunk index is outside the declared total.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PostMapping("/upload/chunk")
    public ResponseEntity<?> uploadChunk(
        @Parameter(description = "This chunk's bytes.")
        @RequestParam("chunk") MultipartFile chunk,

        @Parameter(description = "Client-chosen identifier tying the chunks of one upload together.",
                   example = "0d4c1f8e-2b7a-4c31-9de6-5a0b83f27c14")
        @RequestParam("uploadId") String uploadId,

        @Parameter(description = "Zero-based index of this chunk within the file.", example = "6")
        @RequestParam("chunkIndex") int chunkIndex,

        @Parameter(description = "How many chunks the file was split into.", example = "12")
        @RequestParam("totalChunks") int totalChunks,

        @Parameter(description = "Name of the file being uploaded, kept as metadata only.",
                   example = "RVD-XX-02-DR-A-1200.pdf")
        @RequestParam("fileName") String fileName,

        @Parameter(description = "Project to file the document under. Required on the last chunk.",
                   example = "42")
        @RequestParam(value = "projectId", required = false) Long projectId,

        @Parameter(description = "Display name. Defaults to the file name without its extension.",
                   example = "GA Plan — Level 02")
        @RequestParam(value = "name", required = false) String name,

        @Parameter(description = "What kind of document this is.", example = "DRAWING")
        @RequestParam(value = "documentType", defaultValue = "DRAWING") String documentType,

        @Parameter(description = "Revision identifier as the originator issued it.", example = "P02.1")
        @RequestParam(value = "revision", required = false) String revision,

        @Parameter(description = "Drawing number from the title block.", example = "RVD-XX-02-DR-A-1200")
        @RequestParam(value = "drawingNumber", required = false) String drawingNumber,

        @Parameter(hidden = true) @AuthenticationPrincipal UserDetails principal,
        HttpServletRequest httpRequest
    ) {
        try {
            CHUNKS.computeIfAbsent(uploadId, k -> new java.util.TreeMap<>())
                  .put(chunkIndex, chunk.getBytes());

            int received = CHUNKS.get(uploadId).size();

            // All chunks received — assemble file
            if (received == totalChunks && projectId != null) {
                var project = projectRepo.findById(projectId)
                    .orElseThrow(() -> new ResourceNotFoundException("No such project."));

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

                return ResponseEntity.status(HttpStatus.CREATED)
                    .body(toResponse(documentRepo.save(doc)));
            }

            return ResponseEntity.ok(new ChunkAccepted(uploadId, received, totalChunks));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiProblem.of(
                HttpStatus.INTERNAL_SERVER_ERROR, "storage-write-failed", "Chunk upload failed",
                "The chunk could not be stored. Retry it; if it keeps failing, quote the trace id.",
                httpRequest));
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
