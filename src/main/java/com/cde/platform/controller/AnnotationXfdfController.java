package com.cde.platform.controller;

import com.cde.platform.dto.AnnotationDtos.*;
import com.cde.platform.exception.DocumentProcessingException;
import com.cde.platform.exception.ResourceNotFoundException;
import com.cde.platform.model.*;
import com.cde.platform.openapi.ApiDocumentation;
import com.cde.platform.openapi.StandardErrorResponses;
import com.cde.platform.repository.*;
import com.cde.platform.service.XfdfService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Markup in and out as XFDF, the format other PDF tools read.
 *
 * <p>Separate from {@link AnnotationController} because it is interchange
 * rather than domain work: the endpoints here parse and emit a file format,
 * and everything they do is in service of markup made here opening in
 * Acrobat and markup made in Acrobat opening here. Keeping them together put
 * the file past the §3.3 limit and mixed a format concern into the resource.
 *
 * <p>The paths do not move — both classes map {@code /api/annotations}, and
 * which one serves a request is not something a caller can observe (§3.4).
 */
@RestController
@RequestMapping("/api/annotations")
@Tag(name = ApiDocumentation.TAG_ANNOTATIONS)
@StandardErrorResponses
public class AnnotationXfdfController {

    private final AnnotationRepository annotationRepo;
    private final DocumentRepository   documentRepo;
    private final UserRepository       userRepo;
    private final XfdfService          xfdfService;

    public AnnotationXfdfController(AnnotationRepository annotationRepo,
                                    DocumentRepository documentRepo,
                                    UserRepository userRepo,
                                    XfdfService xfdfService) {
        this.annotationRepo = annotationRepo;
        this.documentRepo = documentRepo;
        this.userRepo = userRepo;
        this.xfdfService = xfdfService;
    }

    @Operation(
        operationId = "exportAnnotationsAsXfdf",
        summary = "Export a document's markup as XFDF",
        description = """
            XFDF is the interchange format Acrobat and most PDF tools read, so markup made here \
            can be opened elsewhere and vice versa.

            The reply is a file download, not JSON.

            Requires the `annotation:read` permission.""")
    @ApiResponse(responseCode = "200", description = "The markup as an XFDF document.",
        content = @Content(mediaType = "application/vnd.adobe.xfdf",
                           schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "404",
        description = "No document with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @GetMapping("/document/{documentId}/xfdf")
    public ResponseEntity<byte[]> exportXfdf(
        @Parameter(description = "Identifier of the document.", example = "1180")
        @PathVariable Long documentId
    ) {
        var docOpt = documentRepo.findById(documentId);
        if (docOpt.isEmpty()) return ResponseEntity.notFound().build();
        var doc  = docOpt.get();
        var anns = annotationRepo.findByDocument_Id(documentId);
        String xfdf = xfdfService.toXfdf(anns, doc.getFileName() != null ? doc.getFileName() : doc.getName());
        String fn   = (doc.getName() != null ? doc.getName() : "document") + ".xfdf";
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fn + "\"")
            .header(HttpHeaders.CONTENT_TYPE, "application/vnd.adobe.xfdf")
            .body(xfdf.getBytes(StandardCharsets.UTF_8));
    }

    @Operation(
        operationId = "importAnnotationsFromXfdf",
        summary = "Import markup from an XFDF file",
        description = """
            Adds the file's markup to the document, attributed to the authenticated caller. \
            Existing markup is left alone — importing adds, it does not replace.

            A file containing no markup is not an error: nothing is imported and the reply says \
            so, because "the file was empty" and "the file was rejected" call for different \
            things from the person who chose it.

            The file is parsed with external entities and DTDs disabled.

            Requires the `annotation:write` permission.""")
    @ApiResponse(responseCode = "200",
        description = "The file was read. `imported` may be zero if it held no markup.")
    @ApiResponse(responseCode = "404",
        description = "No document with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "422",
        description = "The file is not readable XFDF.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PostMapping("/document/{documentId}/xfdf")
    public ResponseEntity<XfdfImportResponse> importXfdf(
        @Parameter(description = "Identifier of the document to add the markup to.", example = "1180")
        @PathVariable Long documentId,
        @Parameter(description = "The XFDF file.")
        @RequestParam("file") MultipartFile file,
        @Parameter(hidden = true) @AuthenticationPrincipal UserDetails principal
    ) {
        var doc = documentRepo.findById(documentId)
            .orElseThrow(() -> new ResourceNotFoundException("No such document."));
        var author = userRepo.findByUsername(principal.getUsername()).orElseThrow();

        try {
            var imported = xfdfService.fromXfdf(file.getBytes());
            if (imported.isEmpty()) {
                return ResponseEntity.ok(new XfdfImportResponse(
                    0, List.of(), "That file contained no markup, so nothing was imported."));
            }

            var saved = imported.stream().map(imp -> {
                var ann = Annotation.builder()
                    .document(doc).author(author)
                    .type(imp.type())
                    .shapeData(imp.shapeData())
                    .comment(imp.comment() != null ? imp.comment() : "")
                    .pageNumber(imp.pageNumber())
                    .status(Annotation.AnnotationStatus.OPEN)
                    .createdAt(java.time.LocalDateTime.now())
                    .build();
                return AnnotationResponse.of(annotationRepo.save(ann));
            }).toList();

            return ResponseEntity.ok(new XfdfImportResponse(saved.size(), saved,
                "Imported %d %s.".formatted(saved.size(),
                    saved.size() == 1 ? "annotation" : "annotations")));

        } catch (Exception e) {
            // The parser's own message names classes and offsets, so it is
            // logged rather than returned.
            throw new DocumentProcessingException(
                "That file could not be read as XFDF. Check it was exported from a PDF tool "
                + "and is not damaged.", e);
        }
    }
}
