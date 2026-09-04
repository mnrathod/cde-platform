package com.cde.platform.conversion.api;

import com.cde.platform.model.ConversionJob;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The wire shapes of the conversion API.
 *
 * <p>Every constraint here is duplicated in the OpenAPI annotation beside it,
 * because §3.5 requires the spec to state what the code enforces — a validation
 * rule that exists only in code is one no generated client and no integrator
 * knows about.
 */
public final class ConversionDtos {

    private ConversionDtos() {
    }

    /**
     * A request to convert whatever is behind a link.
     *
     * @param sourceUrl a short-lived link the integrating application minted:
     *                  an S3 presigned GET, an Azure SAS, a GCS signed URL, a
     *                  Graph download URL. It is fetched once and never stored.
     */
    @Schema(name = "ConversionJobRequest",
            description = """
                A conversion to perform. The link is fetched once, immediately, \
                and is never written to the database, a log, or a message: it is \
                a bearer credential, so only its host is retained on the job \
                record.""")
    public record ConversionJobRequest(

        @Schema(description = """
                    A link to the file to convert, which this server will fetch \
                    once. Must be https unless the deployment has opted out, must \
                    resolve to a public address, and must be on the deployment's \
                    list of permitted storage hosts where one is configured. \
                    Redirects are not followed.""",
                example = "https://files.example.test/drawings/site-plan.dwg?token=synthetic",
                maxLength = 2048,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "A source URL is required.")
        @Size(max = 2048, message = "The source URL may be at most 2048 characters.")
        // Scheme checked here only so an obviously wrong value is a 422 with a
        // clear message rather than a refusal from deeper in. The real decision
        // is FetchDestinationPolicy's, and it checks resolved addresses, which
        // no pattern can.
        @Pattern(regexp = "^https?://.+",
                 message = "The source URL must begin with https:// or http://.")
        String sourceUrl,

        @Schema(description = "What to produce. Only PDF is supported today.",
                example = "PDF",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "A target format is required.")
        ConversionJob.TargetFormat targetFormat
    ) {}

    /**
     * The state of a conversion.
     *
     * <p>Carries no link and no storage path. The result is collected from the
     * download endpoint, which re-checks the caller's permission and tenant —
     * exposing a path here would be an object reference a caller could try to
     * use directly (§5.13.13).
     */
    @Schema(name = "ConversionJobResponse",
            description = "The state of a conversion, and its outcome once it has one.")
    public record ConversionJobResponse(

        @Schema(description = "The job's identifier, used on every other conversion endpoint.",
                example = "3f2a71c4-9b0e-4a2d-8c11-5e7d9a1b3f40",
                requiredMode = Schema.RequiredMode.REQUIRED)
        UUID jobId,

        @Schema(description = """
                    PENDING while queued, RUNNING while working, then one of \
                    SUCCEEDED, FAILED or CANCELLED. The three terminal states \
                    never change again.""",
                example = "RUNNING",
                requiredMode = Schema.RequiredMode.REQUIRED)
        ConversionJob.Status status,

        @Schema(description = """
                    Rough progress, 0 to 100. Never goes backwards, and reaches \
                    100 only on success — a bar sitting at 100 while work \
                    continues has stopped meaning anything.""",
                example = "60", minimum = "0", maximum = "100",
                requiredMode = Schema.RequiredMode.REQUIRED)
        int progressPercent,

        @Schema(description = "The host the file was fetched from. Never the path or query string.",
                example = "files.example.test",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String sourceHost,

        @Schema(description = """
                    What the far end called the file, sanitised, for display \
                    only. Empty when it offered no name.""",
                example = "site-plan.dwg")
        String sourceFileName,

        @Schema(description = "What was produced.", example = "PDF",
                requiredMode = Schema.RequiredMode.REQUIRED)
        ConversionJob.TargetFormat targetFormat,

        @Schema(description = "Size of the converted file in bytes, once it exists.",
                example = "482913", nullable = true)
        Long resultSizeBytes,

        @Schema(description = """
                    Why the job failed, written to be acted on. Present only \
                    for FAILED. Never contains internal detail.""",
                example = "The storage service answered 403 for that link. "
                        + "A signed link that has expired usually answers 403.",
                nullable = true)
        String failureReason,

        @Schema(description = "Whether a cancellation has been asked for but not yet taken effect.",
                example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean cancellationRequested,

        @Schema(description = "When the job was accepted, UTC.",
                example = "2026-09-04T09:12:33", requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDateTime createdAt,

        @Schema(description = "When work began, UTC. Absent while still queued.",
                example = "2026-09-04T09:12:35", nullable = true)
        LocalDateTime startedAt,

        @Schema(description = "When the job reached a terminal state, UTC.",
                example = "2026-09-04T09:13:02", nullable = true)
        LocalDateTime finishedAt
    ) {

        public static ConversionJobResponse of(ConversionJob job) {
            return new ConversionJobResponse(
                job.getPublicId(),
                job.getStatus(),
                job.getProgressPercent(),
                job.getSourceHost(),
                job.getSourceFileName(),
                job.getTargetFormat(),
                job.getResultSizeBytes().orElse(null),
                job.getFailureReason().orElse(null),
                job.isCancellationRequested(),
                job.getCreatedAt(),
                job.getStartedAt().orElse(null),
                job.getFinishedAt().orElse(null));
        }
    }
}
