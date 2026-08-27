package com.cde.platform.upload;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Bounds on a chunked upload in flight.
 *
 * <p>Every one of these exists because the alternative is unbounded, and an
 * unbounded staging area is a denial of service that costs the attacker one
 * request: declare an enormous upload, send one chunk, never return.
 */
@ConfigurationProperties(prefix = "cde.upload")
@Validated
public class UploadStagingProperties {

    /**
     * The largest file that may be assembled from chunks.
     *
     * <p>Two gigabytes because that is roughly the scale of a federated model,
     * which is the case this endpoint exists for. Enforced exactly, as the
     * running total of what has been staged — not estimated from the chunk
     * count.
     */
    @NotNull
    private DataSize maxFileSize = DataSize.ofGigabytes(2);

    /**
     * The largest single chunk accepted.
     *
     * <p>The browser client sends two megabytes. The limit is above that rather
     * than at it, so a client that picks a larger chunk size still works, and
     * far below the multipart limit, so one request cannot stage a large
     * fraction of the file cap on its own.
     */
    @NotNull
    private DataSize maxChunkSize = DataSize.ofMegabytes(8);

    /**
     * How many chunks one upload may be split into.
     *
     * <p>Bounds the index space, so a chunk index is validated against
     * something finite rather than against whatever total the client declared.
     */
    @Min(1)
    private int maxChunks = 4096;

    /**
     * How long an upload with no activity is kept before its chunks are
     * deleted.
     *
     * <p>Long enough to survive a slow or interrupted upload being resumed,
     * short enough that abandoned ones do not accumulate. The sweep is
     * opportunistic — see {@link ChunkedUploadStaging} for why it is not a
     * scheduled job.
     */
    @NotNull
    private Duration stagingExpiry = Duration.ofHours(24);

    /**
     * What an unreachable malware scanner means.
     *
     * <p>Defaults to BEST_EFFORT so that a deployment with no scanner
     * configured still works, and says so in its logs on every upload. A shared
     * Common Data Environment should set REQUIRED: a file admitted unscanned is
     * one every appointed party on the project will open.
     */
    private UploadScanningPolicy scanning = UploadScanningPolicy.BEST_EFFORT;

    /** Where a ClamAV daemon is listening, when one is configured. */
    private String scannerHost = "";

    private int scannerPort = 3310;

    private Duration scannerTimeout = Duration.ofSeconds(30);

    public UploadScanningPolicy getScanning() {
        return scanning;
    }

    public void setScanning(UploadScanningPolicy scanning) {
        this.scanning = scanning;
    }

    public String getScannerHost() {
        return scannerHost;
    }

    public void setScannerHost(String scannerHost) {
        this.scannerHost = scannerHost == null ? "" : scannerHost.trim();
    }

    public int getScannerPort() {
        return scannerPort;
    }

    public void setScannerPort(int scannerPort) {
        this.scannerPort = scannerPort;
    }

    public Duration getScannerTimeout() {
        return scannerTimeout;
    }

    public void setScannerTimeout(Duration scannerTimeout) {
        this.scannerTimeout = scannerTimeout;
    }

    /** Whether a scanner endpoint has actually been named. */
    public boolean hasScannerConfigured() {
        return !scannerHost.isBlank();
    }

    public DataSize getMaxFileSize() {
        return maxFileSize;
    }

    public void setMaxFileSize(DataSize maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    public DataSize getMaxChunkSize() {
        return maxChunkSize;
    }

    public void setMaxChunkSize(DataSize maxChunkSize) {
        this.maxChunkSize = maxChunkSize;
    }

    public int getMaxChunks() {
        return maxChunks;
    }

    public void setMaxChunks(int maxChunks) {
        this.maxChunks = maxChunks;
    }

    public Duration getStagingExpiry() {
        return stagingExpiry;
    }

    public void setStagingExpiry(Duration stagingExpiry) {
        this.stagingExpiry = stagingExpiry;
    }
}
