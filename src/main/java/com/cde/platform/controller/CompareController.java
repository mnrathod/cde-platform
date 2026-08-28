package com.cde.platform.controller;

import com.cde.platform.dto.InspectionDtos.ComparisonResponse;
import com.cde.platform.exception.ConverterOfflineException;
import com.cde.platform.exception.DocumentProcessingException;
import com.cde.platform.exception.ResourceNotFoundException;
import com.cde.platform.model.Document;
import com.cde.platform.openapi.ApiDocumentation;
import com.cde.platform.openapi.StandardErrorResponses;
import com.cde.platform.repository.DocumentRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Difference between two documents.
 *
 * <p>The comparison itself is produced by the conversion service, which knows
 * how to read each format. This endpoint resolves the two documents, checks the
 * caller may see both, and adds the metadata that makes the result readable —
 * which revision was which.
 */
@RestController
@RequestMapping("/api/compare")
@Tag(name = ApiDocumentation.TAG_COMPARISON)
@StandardErrorResponses
public class CompareController {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration COMPARE_TIMEOUT = Duration.ofSeconds(180);

    private final DocumentRepository documentRepo;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT).build();

    private final String converterUrl;

    public CompareController(DocumentRepository documentRepo,
                             @Value("${cde.converter.url}") String converterUrl) {
        this.documentRepo = documentRepo;
        this.converterUrl = converterUrl;
    }

    @Operation(
        operationId = "compareDocuments",
        summary = "Compare two documents",
        description = """
            Reports what differs between two documents — most usefully two revisions of the same \
            drawing, which is the case this exists for.

            Both documents must be visible to the caller. One in another tenant reports `404` \
            rather than `403`, so the reply does not confirm that the identifier exists \
            elsewhere.

            The comparison's own members vary with what was compared, which is why they are \
            carried under `comparison` rather than spread across a shape that would be mostly \
            absent for any given pair.

            Requires the `document:read` permission on both documents.""")
    @ApiResponse(responseCode = "200", description = "The comparison.")
    @ApiResponse(responseCode = "404",
        description = "Either document is not visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "422",
        description = "Either document has no file, or the pair cannot be compared.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "503",
        description = "The conversion service is not reachable. The request was fine and will "
                    + "succeed once it is back.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PostMapping
    public ResponseEntity<ComparisonResponse> compare(@Valid @RequestBody CompareRequest request) {
        Document first = requireReadableWithFile(request.documentId1());
        Document second = requireReadableWithFile(request.documentId2());

        JsonNode comparison = callConverter(first, second);

        return ResponseEntity.ok(new ComparisonResponse(
            comparison.path("success").asBoolean(true),
            orEmpty(first.getName()), orEmpty(second.getName()),
            orEmpty(first.getFileName()), orEmpty(second.getFileName()),
            orEmpty(first.getRevision()), orEmpty(second.getRevision()),
            comparison.hasNonNull("error") ? comparison.get("error").asText() : null,
            toMap(comparison)));
    }

    @Schema(name = "CompareRequest", description = "The two documents to compare.")
    public record CompareRequest(
        @Schema(description = "The earlier document, shown as the left-hand side.", example = "1180",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Long documentId1,

        @Schema(description = "The later document, shown as the right-hand side.", example = "1195",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Long documentId2
    ) {}

    /**
     * Loads a document the caller may see and that has a file behind it.
     *
     * @throws ResourceNotFoundException     if it is not visible to the caller
     * @throws DocumentProcessingException   if it has no file to compare
     */
    private Document requireReadableWithFile(Long documentId) {
        Document document = documentRepo.findById(documentId)
            .orElseThrow(() -> new ResourceNotFoundException("No such document."));

        if (document.getFilePath() == null || !Files.exists(Paths.get(document.getFilePath()))) {
            throw new DocumentProcessingException(
                "That document has no file behind it, so there is nothing to compare.");
        }
        return document;
    }

    private JsonNode callConverter(Document first, Document second) {
        ObjectNode body = mapper.createObjectNode();
        body.put("path1", Paths.get(first.getFilePath()).toAbsolutePath().toString());
        body.put("path2", Paths.get(second.getFilePath()).toAbsolutePath().toString());
        body.put("contentType1", orEmpty(first.getFileType()));
        body.put("contentType2", orEmpty(second.getFileType()));

        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(converterUrl + "/compare"))
                .timeout(COMPARE_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            return mapper.readTree(response.body());

        } catch (java.net.ConnectException e) {
            // Distinguished from a comparison failure because the remedy
            // differs: the request was fine and will work once the service is
            // back, which is a 503 the client can offer to retry.
            throw new ConverterOfflineException(converterUrl);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DocumentProcessingException("The comparison was interrupted.", e);
        } catch (Exception e) {
            throw new DocumentProcessingException(
                "Those two documents could not be compared.", e);
        }
    }

    /** The converter's own members, minus the ones lifted into named fields. */
    private Map<String, Object> toMap(JsonNode comparison) {
        Map<String, Object> members = new LinkedHashMap<>();
        comparison.properties().forEach(entry -> {
            if (!"success".equals(entry.getKey()) && !"error".equals(entry.getKey())) {
                members.put(entry.getKey(), mapper.convertValue(entry.getValue(), Object.class));
            }
        });
        return members;
    }

    private String orEmpty(String value) {
        return value != null ? value : "";
    }
}
