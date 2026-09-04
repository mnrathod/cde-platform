package com.cde.platform.conversion.api;

import com.cde.platform.conversion.ConversionJobService;
import com.cde.platform.conversion.api.ConversionDtos.ConversionJobRequest;
import com.cde.platform.conversion.api.ConversionDtos.ConversionJobResponse;
import com.cde.platform.exception.ResourceNotFoundException;
import com.cde.platform.model.ConversionJob;
import com.cde.platform.openapi.ApiDocumentation;
import com.cde.platform.openapi.StandardErrorResponses;
import com.cde.platform.repository.UserRepository;
import com.cde.platform.storage.StorageCategory;
import com.cde.platform.storage.StorageKey;
import com.cde.platform.storage.StorageProperties;
import com.cde.platform.storage.StorageProvider;
import com.cde.platform.tenancy.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import jakarta.validation.Valid;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Converting a document behind a link an integrating application supplied.
 *
 * <p>ADR 12's integration surface. The host CDE mints a short-lived link and
 * posts it here; this server fetches it once, examines it, converts it, and
 * holds the result for collection. Four storage platforms — SharePoint via
 * Graph, S3, Azure Blob, Google Cloud Storage — collapse into one code path,
 * and none of their credentials is ever held by this product.
 *
 * <p>Every operation is bulk by §7.1's definition, so submission returns
 * {@code 202} with a job to poll rather than the converted file. There is no
 * synchronous variant and there should not be: a two-gigabyte model cannot be
 * converted inside a second, and an endpoint that sometimes takes ten minutes
 * is one every client eventually times out against.
 */
@RestController
@RequestMapping("/api/conversions")
// Same condition as the rest of the pipeline: an air-gapped deployment gets no
// endpoint rather than one that answers 500 because its service is absent.
@ConditionalOnProperty(prefix = "cde.fetch", name = "enabled", havingValue = "true")
@Tag(name = ApiDocumentation.TAG_CONVERSIONS)
@StandardErrorResponses
public class ConversionJobController {

    private final ConversionJobService conversions;
    private final UserRepository users;
    private final StorageProvider storage;
    private final String environment;

    public ConversionJobController(ConversionJobService conversions,
                                   UserRepository users,
                                   StorageProvider storage,
                                   StorageProperties storageProperties) {
        this.conversions = conversions;
        this.users = users;
        this.storage = storage;
        this.environment = storageProperties.getEnvironment();
    }

    @Operation(
        operationId = "submitConversion",
        summary = "Submit a document for conversion",
        description = """
            Accepts a link to a file and converts it, asynchronously. Returns at once \
            with a job to poll; the conversion itself runs on a queue.

            The link is fetched exactly once and is **never stored** — not in the \
            database, not in a log, not in a message. It is a bearer credential, so \
            only its host is kept on the job record. A link that has expired by the \
            time the job runs fails the job rather than the submission.

            The destination must resolve to a public address and must be on this \
            deployment's list of permitted storage hosts where one is configured. \
            Redirects are not followed.

            Send an `Idempotency-Key` header so a timed-out retry returns the same job \
            rather than converting the file twice.

            Requires the `document:convert` permission.""")
    @ApiResponse(responseCode = "202",
        description = "Accepted. The `Location` header names the job to poll.")
    @ApiResponse(responseCode = "422",
        description = "The link may not be fetched — wrong scheme, an unlisted host, "
                    + "or an address inside this deployment's own network.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "429",
        description = "The conversion queue is full. Retry after the interval in "
                    + "`Retry-After`.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PreAuthorize("hasAuthority('document:convert')")
    @PostMapping
    public ResponseEntity<ConversionJobResponse> submit(
        @Valid @RequestBody ConversionJobRequest request,

        @Parameter(description = """
                       A key of the client's choosing making this submission safe to \
                       retry. Repeating a submission with the same key returns the \
                       job created the first time instead of starting another.""",
                   example = "9c1f4d2e-7a10-4c3b-9f22-6e8b0a5d1c47")
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,

        @Parameter(hidden = true) @AuthenticationPrincipal UserDetails principal
    ) {
        ConversionJob job = conversions.submit(
            URI.create(request.sourceUrl()),
            request.targetFormat(),
            currentUserId(principal),
            idempotencyKey);

        return ResponseEntity
            .accepted()
            .location(URI.create("/api/conversions/" + job.getPublicId()))
            .body(ConversionJobResponse.of(job));
    }

    @Operation(
        operationId = "getConversion",
        summary = "Read the state of a conversion",
        description = """
            Poll this until `status` is one of SUCCEEDED, FAILED or CANCELLED, which \
            never change again. `progressPercent` moves forward only and reaches 100 \
            only on success.

            A job belonging to another tenant answers 404, exactly as one that does not \
            exist — a caller must not be able to tell the two apart.

            Requires the `document:convert` permission.""")
    @ApiResponse(responseCode = "200", description = "The job's current state.")
    @ApiResponse(responseCode = "404",
        description = "No such job is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PreAuthorize("hasAuthority('document:convert')")
    @GetMapping("/{jobId}")
    public ConversionJobResponse get(
        @Parameter(description = "Identifier returned when the job was submitted.",
                   example = "3f2a71c4-9b0e-4a2d-8c11-5e7d9a1b3f40")
        @PathVariable UUID jobId
    ) {
        return ConversionJobResponse.of(requireJob(jobId));
    }

    @Operation(
        operationId = "listConversions",
        summary = "List this organisation's conversions",
        description = """
            Newest first. Scoped to the caller's own organisation; no parameter widens \
            that.

            Requires the `document:convert` permission.""")
    @ApiResponse(responseCode = "200", description = "One page of jobs, newest first.")
    @PreAuthorize("hasAuthority('document:convert')")
    @GetMapping
    public List<ConversionJobResponse> list(
        @Parameter(description = "Zero-based page number.", example = "0")
        @RequestParam(defaultValue = "0") int page,

        @Parameter(description = "Jobs per page, at most 100.", example = "20")
        @RequestParam(defaultValue = "20") int size
    ) {
        // Clamped rather than validated into a 422: a caller asking for a
        // thousand rows wants as many as they can have, and refusing the whole
        // request teaches them nothing a cap does not.
        Page<ConversionJob> jobs = conversions.list(
            PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100)));
        return jobs.map(ConversionJobResponse::of).getContent();
    }

    @Operation(
        operationId = "cancelConversion",
        summary = "Ask a conversion to stop",
        description = """
            Cancellation is co-operative: the worker notices between stages, so the job \
            may still report RUNNING for a moment afterwards with \
            `cancellationRequested` true.

            Idempotent, and safe to call on a job that has already finished — that \
            answers with the job's final state rather than an error, because racing a \
            job to completion is normal rather than a client mistake.

            Requires the `document:convert` permission.""")
    @ApiResponse(responseCode = "200", description = "The job's state after the request.")
    @ApiResponse(responseCode = "404",
        description = "No such job is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PreAuthorize("hasAuthority('document:convert')")
    @DeleteMapping("/{jobId}")
    public ConversionJobResponse cancel(
        @Parameter(description = "Identifier of the job to stop.",
                   example = "3f2a71c4-9b0e-4a2d-8c11-5e7d9a1b3f40")
        @PathVariable UUID jobId
    ) {
        return conversions.requestCancellation(jobId)
            .map(ConversionJobResponse::of)
            .orElseThrow(() -> notFound(jobId));
    }

    @Operation(
        operationId = "downloadConversionResult",
        summary = "Download a completed conversion",
        description = """
            Streams the converted PDF. Available only once `status` is SUCCEEDED.

            The response is served as an attachment with `nosniff`, and no storage path \
            is exposed anywhere in this API — the object is reached through this \
            endpoint, which re-checks the caller's permission and organisation on every \
            request (§5.13).

            Requires the `document:convert` permission.""")
    @ApiResponse(responseCode = "200", description = "The converted PDF.",
        content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE))
    @ApiResponse(responseCode = "404",
        description = "No such job is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "409",
        description = "The job has not succeeded, so there is nothing to download yet.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PreAuthorize("hasAuthority('document:convert')")
    @GetMapping("/{jobId}/content")
    public ResponseEntity<InputStreamResource> download(
        @Parameter(description = "Identifier of a job that has succeeded.",
                   example = "3f2a71c4-9b0e-4a2d-8c11-5e7d9a1b3f40")
        @PathVariable UUID jobId
    ) {
        ConversionJob job = requireJob(jobId);
        String objectId = job.getResultObjectId().orElseThrow(() ->
            new com.cde.platform.exception.ResourceConflictException(
                "That conversion has not produced a file. Its status is "
                + job.getStatus() + "."));

        // The key is rebuilt from the caller's own tenant, never from anything
        // in the request, so a job row cannot name an object outside its
        // tenant's prefix even if one were somehow written that way (§11).
        StorageKey key = new StorageKey(environment, TenantContext.requireTenantId(),
                                        StorageCategory.DERIVATIVE, objectId);

        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"converted-" + jobId + ".pdf\"")
            .header("X-Content-Type-Options", "nosniff")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(new InputStreamResource(storage.retrieve(key)));
    }

    private ConversionJob requireJob(UUID jobId) {
        return conversions.findByPublicId(jobId).orElseThrow(() -> notFound(jobId));
    }

    /**
     * The same answer for another tenant's job as for one that never existed.
     *
     * <p>RLS has already filtered it out before the service sees a row, so this
     * is not a decision made here so much as the only information available —
     * which is the point (§5.5).
     */
    private ResourceNotFoundException notFound(UUID jobId) {
        return new ResourceNotFoundException("No conversion job " + jobId + " was found.");
    }

    private long currentUserId(UserDetails principal) {
        return users.findByUsername(principal.getUsername())
            .orElseThrow(() -> new IllegalStateException(
                "An authenticated principal has no user record."))
            .getId();
    }
}
