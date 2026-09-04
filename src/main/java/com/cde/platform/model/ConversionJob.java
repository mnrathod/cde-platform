package com.cde.platform.model;

import com.cde.platform.tenancy.TenantAssigningListener;
import com.cde.platform.tenancy.TenantScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * One asynchronous document conversion, from submission to outcome.
 *
 * <p>Converting a document is bulk work by §7.1's definition — its cost scales
 * with the file, and a federated model does not convert inside a second — so
 * submission returns a job and the work happens on a queue. This is that job.
 *
 * <h2>Why there are no setters for status</h2>
 * The lifecycle is {@code PENDING → RUNNING → SUCCEEDED | FAILED | CANCELLED},
 * and each move has something that must be true alongside it: a job that
 * succeeded has a result, one that failed has a reason, one that finished has
 * a finish time. A {@code setStatus} lets a caller write half of that, and the
 * row is then a lie the API has to paper over — a job reporting success with
 * nothing to download. So the moves are methods that set every field the move
 * implies, and refuse from a state where the move makes no sense.
 *
 * <p>The database repeats the invariants as check constraints. That is not
 * redundancy: this class governs what the application does, and the
 * constraints govern what a migration, a fix-up script or a future repository
 * method can do.
 *
 * <h2>What is deliberately absent</h2>
 * The source URL. It is a presigned bearer credential, and persisting it would
 * put it in every backup and replica — see the migration for the full
 * reasoning. Only the host is kept.
 */
@Entity
@Table(name = "conversion_jobs")
@EntityListeners(TenantAssigningListener.class)
public class ConversionJob implements TenantScoped {

    /** Where a job can be, and what it can still become. */
    public enum Status {
        PENDING,
        RUNNING,
        SUCCEEDED,
        FAILED,
        CANCELLED;

        boolean isTerminal() {
            return this == SUCCEEDED || this == FAILED || this == CANCELLED;
        }
    }

    /** What the job produces. One value today; an enum so adding one is a decision. */
    public enum TargetFormat {
        PDF
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /**
     * The identifier the API exposes. A sequential primary key in a URL invites
     * a caller to try the one before it; RLS refuses them, but the number of
     * jobs in the system should not be readable from a URL bar either.
     */
    @Column(name = "public_id", nullable = false, updatable = false)
    private UUID publicId;

    @Column(name = "submitted_by", nullable = false, updatable = false)
    private Long submittedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.PENDING;

    /**
     * The host of the source link, and nothing else. Never the path or query
     * string — the query string is where a presigned URL keeps its signature.
     */
    @Column(name = "source_host", nullable = false, length = 255, updatable = false)
    private String sourceHost;

    /** Sanitised, display-only. Never used to decide where anything is written. */
    @Column(name = "source_file_name", nullable = false, length = 255)
    private String sourceFileName = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "target_format", nullable = false, length = 16, updatable = false)
    private TargetFormat targetFormat;

    @Column(name = "result_object_id", length = 160)
    private String resultObjectId;

    @Column(name = "result_size_bytes")
    private Long resultSizeBytes;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "progress_percent", nullable = false)
    private short progressPercent = 0;

    @Column(name = "cancellation_requested_at")
    private LocalDateTime cancellationRequestedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    protected ConversionJob() {
        // JPA.
    }

    /**
     * A newly submitted job, pending.
     *
     * <p>Takes the host rather than the URL so there is no call site at which
     * the credential could be handed to something that persists it.
     */
    public static ConversionJob submitted(UUID publicId,
                                          long submittedBy,
                                          String sourceHost,
                                          TargetFormat targetFormat) {
        ConversionJob job = new ConversionJob();
        job.publicId = publicId;
        job.submittedBy = submittedBy;
        job.sourceHost = sourceHost;
        job.targetFormat = targetFormat;
        job.status = Status.PENDING;
        job.createdAt = LocalDateTime.now();
        return job;
    }

    /** @throws IllegalStateException if the job is not pending */
    public void begin() {
        requireStatus(Status.PENDING, "start");
        this.status = Status.RUNNING;
        this.startedAt = LocalDateTime.now();
    }

    /**
     * Records progress. Never moves backwards and never reaches 100 before
     * completion: a progress bar that sits at 100% while work continues is a
     * bar that has stopped meaning anything.
     */
    public void reportProgress(int percent) {
        if (status != Status.RUNNING) {
            return;
        }
        short bounded = (short) Math.clamp(percent, 0, 99);
        if (bounded > progressPercent) {
            this.progressPercent = bounded;
        }
    }

    /** The far end's name for the file, already sanitised by the caller. */
    public void recordSourceFileName(String sanitisedName) {
        this.sourceFileName = sanitisedName == null ? "" : sanitisedName;
    }

    /** @throws IllegalStateException if the job is not running */
    public void succeed(String resultObjectId, long resultSizeBytes) {
        requireStatus(Status.RUNNING, "complete");
        this.resultObjectId = resultObjectId;
        this.resultSizeBytes = resultSizeBytes;
        this.progressPercent = 100;
        this.status = Status.SUCCEEDED;
        this.finishedAt = LocalDateTime.now();
    }

    /**
     * Fails the job with a reason written for whoever submitted it.
     *
     * <p>Permitted from PENDING as well as RUNNING: startup recovery fails
     * jobs that never started, and a submission refused before the fetch
     * begins has the same shape.
     *
     * @param reason what happened and what to do next (§1.4). Never a stack
     *               trace, an internal address, or a resolved address.
     */
    public void fail(String reason) {
        if (status.isTerminal()) {
            throw new IllegalStateException(
                "A job that has already " + status + " cannot fail.");
        }
        this.failureReason = reason;
        this.status = Status.FAILED;
        this.finishedAt = LocalDateTime.now();
    }

    /**
     * Asks the executor to stop. Co-operative: it notices between chunks, so
     * this records the request and {@link #cancel()} records the outcome.
     *
     * @return whether the request was recorded. False for a job that has
     *         already finished, which is not an error — it is the answer.
     */
    public boolean requestCancellation() {
        if (status.isTerminal()) {
            return false;
        }
        if (cancellationRequestedAt == null) {
            this.cancellationRequestedAt = LocalDateTime.now();
        }
        return true;
    }

    /** Records that the executor actually stopped. */
    public void cancel() {
        if (status.isTerminal()) {
            throw new IllegalStateException(
                "A job that has already " + status + " cannot be cancelled.");
        }
        this.status = Status.CANCELLED;
        this.finishedAt = LocalDateTime.now();
    }

    public boolean isCancellationRequested() {
        return cancellationRequestedAt != null;
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    private void requireStatus(Status required, String verb) {
        if (status != required) {
            throw new IllegalStateException(
                "A job that is " + status + " cannot " + verb + "; it must be "
                + required + ".");
        }
    }

    public Long getId() {
        return id;
    }

    @Override
    public Long getTenantId() {
        return tenantId;
    }

    @Override
    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public Long getSubmittedBy() {
        return submittedBy;
    }

    public Status getStatus() {
        return status;
    }

    public String getSourceHost() {
        return sourceHost;
    }

    public String getSourceFileName() {
        return sourceFileName;
    }

    public TargetFormat getTargetFormat() {
        return targetFormat;
    }

    public Optional<String> getResultObjectId() {
        return Optional.ofNullable(resultObjectId);
    }

    public Optional<Long> getResultSizeBytes() {
        return Optional.ofNullable(resultSizeBytes);
    }

    public Optional<String> getFailureReason() {
        return Optional.ofNullable(failureReason);
    }

    public short getProgressPercent() {
        return progressPercent;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public Optional<LocalDateTime> getStartedAt() {
        return Optional.ofNullable(startedAt);
    }

    public Optional<LocalDateTime> getFinishedAt() {
        return Optional.ofNullable(finishedAt);
    }
}
