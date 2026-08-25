package com.cde.platform.controller;

import com.cde.platform.dto.DocumentDtos.*;
import com.cde.platform.exception.ApiProblem;
import com.cde.platform.exception.ResourceNotFoundException;
import com.cde.platform.model.*;
import com.cde.platform.openapi.ApiDocumentation;
import com.cde.platform.openapi.StandardErrorResponses;
import com.cde.platform.repository.*;
import com.cde.platform.upload.ChunkedUploadStaging;
import com.cde.platform.upload.StoredFileName;
import com.cde.platform.upload.UploadRejectedException;
import com.cde.platform.upload.UploadedMediaType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Getting bytes into the system: one request for a small file, chunks for a
 * large one.
 *
 * <p>Separate from {@link DocumentController} because ingest is a different
 * concern from the rest of a document's life, with its own rules — streaming,
 * per-tenant staging, size limits, and the deliberate distinction between the
 * name a client sends and the name bytes are stored under. Keeping it here
 * also keeps both files inside the size limit, which the combined one had
 * outgrown.
 */
@RestController
@RequestMapping("/api/documents")
@Tag(name = ApiDocumentation.TAG_DOCUMENTS)
@StandardErrorResponses
public class DocumentUploadController {

    /**
     * Largest SVG kept inline in the database alongside the file.
     *
     * <p>The viewer renders an SVG as markup, so its source is held as text
     * rather than read off disk on every request. That is only reasonable
     * while it is small: without a bound it is a way to put an arbitrary
     * fraction of any uploaded file into a column.
     */
    private static final long MAX_INLINE_VECTOR_BYTES = 2L * 1024 * 1024;

    private final DocumentRepository documentRepo;
    private final ProjectRepository projectRepo;
    private final UserRepository userRepo;
    private final ChunkedUploadStaging staging;

    @Value("${cde.storage.upload-dir}")
    private String uploadDir;

    public DocumentUploadController(DocumentRepository documentRepo, ProjectRepository projectRepo,
                                    UserRepository userRepo, ChunkedUploadStaging staging) {
        this.documentRepo = documentRepo;
        this.projectRepo = projectRepo;
        this.userRepo = userRepo;
        this.staging = staging;
    }

    @Operation(
        operationId = "uploadDocument",
        summary = "Upload a document in one request",
        description = """
            Multipart upload for a file small enough to send in a single request. For a large file \
            use the chunked route, which survives an interrupted connection.

            The stored name is generated server-side; the name the client sent is kept as metadata \
            for display and for the download header only, and is never used as a path.

            The media type is taken from the file's extension, falling back to what the browser \
            claimed. That is a rendering hint, not a security check — it decides which viewer \
            opens the file, and the formats that matter here are drawing and model formats that \
            content sniffing reports as generic binary. Do not read it as a statement about what \
            the bytes are.

            The body is streamed to storage rather than buffered, so the size a deployment can \
            accept is a matter of disk and configuration rather than of heap.

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
        description = "A required part is missing, `documentType` is not a recognised value, or "
                    + "the file is larger than this deployment accepts.",
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
            // Streamed to disk, never held whole. A federated model is the
            // case this has to survive, and reading one into a byte[] first
            // meant the largest file the deployment could accept was a
            // fraction of one request thread's share of the heap.
            String storedName = StoredFileName.forStorage(file.getOriginalFilename());
            String displayName = StoredFileName.forDisplay(file.getOriginalFilename());
            Path dest = Paths.get(uploadDir, projectId.toString()).resolve(storedName);

            long storedBytes;
            try (var incoming = file.getInputStream()) {
                storedBytes = staging.streamTo(incoming, dest);
            }
            if (storedBytes > staging.maxFileSizeBytes()) {
                Files.deleteIfExists(dest);
                throw new UploadRejectedException(
                    "The file is larger than this deployment accepts.");
            }

            var uploader = userRepo.findByUsername(principal.getUsername()).orElseThrow();
            String ct = UploadedMediaType.of(displayName, file.getContentType());

            // Only an SVG keeps its source, and only a small one: the viewer
            // renders it as markup, so it is held as text rather than read
            // back off disk each time. The bound is what stops that being a
            // way to put an arbitrary amount of a file into a database column.
            String vectorData = null;
            if ("image/svg+xml".equals(ct) && storedBytes <= MAX_INLINE_VECTOR_BYTES) {
                String content = Files.readString(dest, java.nio.charset.StandardCharsets.UTF_8);
                if (content.contains("<svg") || content.contains("<SVG")) {
                    vectorData = content;
                }
            }

            var doc = Document.builder()
                .name(name)
                .description(description)
                .fileName(displayName)
                .filePath(dest.toString())
                .fileType(ct)
                .fileSize(storedBytes)
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
        operationId = "uploadDocumentChunk",
        summary = "Send one chunk of a large upload",
        description = """
            Chunks may arrive in any order. Each is acknowledged with a count of what has arrived \
            so far; the chunk that completes the set assembles the file and returns the created \
            document instead, so a client tells completion from progress by the shape of the reply.

            The upload identifier is chosen by the client, and an upload is scoped to the tenant \
            that sent it: two tenants using the same identifier do not share chunks, cannot see \
            each other's, and cannot complete or contaminate each other's file. Within one tenant \
            it should still be unique per upload, because there it does identify the upload.

            Re-sending a chunk replaces it, so an interrupted upload can be resumed by repeating \
            whatever did not arrive.

            Limits, all configurable per deployment: at most 4096 chunks, 8 MB per chunk, and 2 GB \
            assembled. A chunk index outside the declared total, or a total outside that range, \
            is refused with `422`. Chunks of an upload nothing has added to for 24 hours are \
            deleted.

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
        description = "A required part is missing, the chunk index is outside the declared total, "
                    + "the total is outside the permitted range, the chunk is over the per-chunk "
                    + "limit, or the upload has reached the maximum file size. The `detail` names "
                    + "which, and the limit it exceeded.",
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
        // Staged per tenant, on disk, streamed. UploadRejectedException is
        // deliberately not caught here: it carries a message the caller can
        // act on and belongs as a 422, and the blanket catch that used to sit
        // around this turned every such refusal into an opaque 500.
        try {
            int received;
            try (var incoming = chunk.getInputStream()) {
                received = staging.stage(uploadId, chunkIndex, totalChunks,
                                         incoming, chunk.getSize());
            }

            boolean complete = received == totalChunks && projectId != null;
            if (!complete) {
                return ResponseEntity.ok(new ChunkAccepted(uploadId, received, totalChunks));
            }

            var project = projectRepo.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("No such project."));

            String storedName  = StoredFileName.forStorage(fileName);
            String displayName = StoredFileName.forDisplay(fileName);
            Path dest = Paths.get(uploadDir, projectId.toString()).resolve(storedName);

            long storedBytes = staging.assembleInto(uploadId, totalChunks, dest);

            var uploader = userRepo.findByUsername(principal.getUsername()).orElseThrow();

            var doc = Document.builder()
                .name(name != null ? name : displayName.replaceAll("\\.[^.]+$", ""))
                .fileName(displayName).filePath(dest.toString())
                .fileType(UploadedMediaType.of(displayName, chunk.getContentType()))
                .fileSize(storedBytes)
                .documentType(Document.DocumentType.valueOf(documentType))
                .revision(revision).drawingNumber(drawingNumber)
                .project(project).uploadedBy(uploader)
                .status(Document.DocumentStatus.DRAFT)
                .createdAt(java.time.LocalDateTime.now())
                .build();

            return ResponseEntity.status(HttpStatus.CREATED)
                .body(toResponse(documentRepo.save(doc)));

        } catch (IOException e) {
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
