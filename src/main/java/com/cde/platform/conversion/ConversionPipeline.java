package com.cde.platform.conversion;

import com.cde.platform.fetch.ContentFetchFailedException;
import com.cde.platform.fetch.FetchNotPermittedException;
import com.cde.platform.fetch.RemoteContentFetcher;
import com.cde.platform.service.ConverterService;
import com.cde.platform.storage.StorageCategory;
import com.cde.platform.storage.StorageKey;
import com.cde.platform.storage.StorageMetadata;
import com.cde.platform.storage.StorageProperties;
import com.cde.platform.storage.StorageProvider;
import com.cde.platform.tenancy.TenantContext;
import com.cde.platform.upload.StoredFileName;
import com.cde.platform.upload.UploadAdmissionService;
import com.cde.platform.upload.UploadRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Fetch, admit, convert, store — the work one conversion job actually does.
 *
 * <p>Each stage is one that already exists somewhere in this application, and
 * none is reimplemented here. The fetch and its SSRF guard are the fetch
 * package's; deciding what the bytes are, scanning them and refusing active
 * content is {@link UploadAdmissionService}'s, which the upload endpoint
 * already uses; conversion is the converter sidecar's; storage is the
 * provider abstraction's. This class is the order they happen in and what
 * happens when one of them says no.
 *
 * <p>Nothing here holds a file in memory (§7.7). The fetch streams to disk,
 * the converter streams to disk, and the result is streamed into storage.
 *
 * <p>Failure messages are written for whoever submitted the job (§1.4) and
 * never carry internals — no stack traces, no internal hosts, no resolved
 * addresses. Everything that throws is turned into one of those before it
 * reaches the job row.
 */
public class ConversionPipeline {

    private static final Logger log = LoggerFactory.getLogger(ConversionPipeline.class);

    private final RemoteContentFetcher fetcher;
    private final UploadAdmissionService admission;
    private final ConverterService converter;
    private final StorageProvider storage;
    private final ConversionJobStateWriter state;
    private final ConversionJobProperties properties;
    private final Path workRoot;
    private final String environment;

    public ConversionPipeline(RemoteContentFetcher fetcher,
                              UploadAdmissionService admission,
                              ConverterService converter,
                              StorageProvider storage,
                              ConversionJobStateWriter state,
                              ConversionJobProperties properties,
                              StorageProperties storageProperties,
                              @Value("${cde.storage.upload-dir}") String uploadDir) {
        this.fetcher = fetcher;
        this.admission = admission;
        this.converter = converter;
        this.storage = storage;
        this.state = state;
        this.properties = properties;
        this.workRoot = Path.of(uploadDir).resolve("conversion");
        this.environment = storageProperties.getEnvironment();
    }

    /**
     * Runs one job to a terminal state. Never throws: a worker that lost its
     * thread to an exception would leave the job at RUNNING for ever, so
     * everything is turned into an outcome the submitter can read.
     */
    public void run(ConversionRequest request) {
        UUID jobId = request.jobPublicId();
        // Every directory is tenant-namespaced, like every storage prefix and
        // cache key (§5.6). Server-generated names throughout, so nothing the
        // far end said can influence a path (§5.13.6).
        Path workDirectory = workRoot.resolve(String.valueOf(request.tenantId()))
                                     .resolve(jobId.toString());
        try {
            TenantContext.runAsTenant(request.tenantId(),
                () -> state.markRunning(jobId));
            convert(request, workDirectory);
        } catch (Exception e) {
            recordFailure(request, e);
        } finally {
            // Always, on every path: a fetched original and a converted copy
            // left behind would accumulate at the rate documents are submitted.
            deleteWorkDirectory(workDirectory);
        }
    }

    private void convert(ConversionRequest request, Path workDirectory) throws IOException {
        UUID jobId = request.jobPublicId();
        Path quarantined = workDirectory.resolve("fetched");
        Path admitted = workDirectory.resolve("admitted");
        Path converted = workDirectory.resolve("converted.pdf");

        if (stopIfCancelled(request)) {
            return;
        }

        var fetched = fetcher.fetchTo(request.sourceUrl(), quarantined);
        String displayName = StoredFileName.forDisplay(fetched.declaredFileName());
        TenantContext.runAsTenant(request.tenantId(), () -> {
            state.recordSourceFileName(jobId, displayName);
            state.recordProgress(jobId, 40);
        });

        if (stopIfCancelled(request)) {
            return;
        }

        var admittedFile = admission.admit(quarantined, admitted, displayName);
        TenantContext.runAsTenant(request.tenantId(), () -> state.recordProgress(jobId, 60));

        if (stopIfCancelled(request)) {
            return;
        }

        long convertedBytes = converter.convertToPdfFile(
            admittedFile.path(), admittedFile.detectedType(), converted,
            properties.getConversionTimeout());
        TenantContext.runAsTenant(request.tenantId(), () -> state.recordProgress(jobId, 85));

        if (stopIfCancelled(request)) {
            return;
        }

        String objectId = storeResult(request, converted);
        TenantContext.runAsTenant(request.tenantId(),
            () -> state.markSucceeded(jobId, objectId, convertedBytes));

        log.info("Conversion job {} produced {} bytes for tenant {}",
                 jobId, convertedBytes, request.tenantId());
    }

    /**
     * Streams the converted file into object storage under a server-generated
     * key. The key carries the tenant by construction, so there is no call site
     * at which the prefix could be forgotten (§11).
     */
    private String storeResult(ConversionRequest request, Path converted) throws IOException {
        String objectId = UUID.randomUUID().toString().replace("-", "") + ".pdf";
        StorageKey key = new StorageKey(
            environment, request.tenantId(), StorageCategory.DERIVATIVE, objectId);

        try (InputStream content = Files.newInputStream(converted)) {
            storage.store(key, content,
                StorageMetadata.forUpload("application/pdf", objectId));
        }
        return objectId;
    }

    /**
     * @return whether the job was cancelled, in which case the caller stops.
     *         Checked between stages rather than during one: cancellation is
     *         co-operative, and a stage that has started should finish or fail
     *         on its own terms rather than be torn down mid-write.
     */
    private boolean stopIfCancelled(ConversionRequest request) {
        UUID jobId = request.jobPublicId();
        boolean cancelled = TenantContext.callAsTenant(request.tenantId(),
            () -> state.isCancellationRequested(jobId));
        if (cancelled) {
            TenantContext.runAsTenant(request.tenantId(), () -> state.markCancelled(jobId));
            log.info("Conversion job {} stopped at the submitter's request", jobId);
        }
        return cancelled;
    }

    /**
     * Turns whatever went wrong into something the submitter can act on.
     *
     * <p>The exception itself is logged, with its stack, for whoever operates
     * the system. What reaches the job row is the sentence, never the trace:
     * a caller reading "NullPointerException at line 84" learns nothing they
     * can use and something they should not have.
     */
    private void recordFailure(ConversionRequest request, Exception cause) {
        String reason = explain(cause);
        log.warn("Conversion job {} failed for tenant {}",
                 request.jobPublicId(), request.tenantId(), cause);
        TenantContext.runAsTenant(request.tenantId(),
            () -> state.markFailed(request.jobPublicId(), reason));
    }

    private String explain(Exception cause) {
        return switch (cause) {
            case FetchNotPermittedException e -> e.getMessage();
            case ContentFetchFailedException e -> e.getMessage();
            case UploadRejectedException e -> e.getMessage();
            case ConverterService.ConversionRefusedException e ->
                "That file could not be converted to PDF: " + e.getMessage();
            case com.cde.platform.exception.ConverterOfflineException e ->
                "The conversion service is not available at the moment. "
                + "The job can be submitted again shortly.";
            // Anything unanticipated. The submitter gets a sentence and the
            // correlation id; the detail is in the log, where it belongs.
            default -> "The conversion did not complete because of an "
                     + "unexpected error. Support can trace it from the job id.";
        };
    }

    private void deleteWorkDirectory(Path workDirectory) {
        if (!Files.exists(workDirectory)) {
            return;
        }
        try (var entries = Files.walk(workDirectory)) {
            entries.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("Could not delete {} from the conversion work area", path, e);
                }
            });
        } catch (IOException e) {
            // A work area that fills is a disk-exhaustion problem worth
            // knowing about, but not worth replacing the job's real outcome.
            log.warn("Could not clear the conversion work area {}", workDirectory, e);
        }
    }
}
