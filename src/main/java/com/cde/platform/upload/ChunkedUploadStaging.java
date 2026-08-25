package com.cde.platform.upload;

import com.cde.platform.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Holds the chunks of an upload in flight, on disk, scoped to one tenant.
 *
 * <p>They used to live in a static map keyed by the upload identifier alone.
 * That identifier is chosen by the client, so two tenants using the same string
 * shared a store: one could contaminate the other's assembled file with its own
 * bytes, or occupy an index the other would never send and stop their upload
 * completing at all. Neither goes near the database, so Row-Level Security —
 * which is what protects everything else here — never saw any of it.
 *
 * <p>The staging key is a digest of the tenant, the identifier and the declared
 * chunk count. Deriving it rather than using the client's string does three
 * things at once: it separates tenants, it makes the name safe to use as a
 * directory by construction rather than by sanitising, and it keeps the
 * client's identifier off the disk. Including the count means a client that
 * changes its mind about how many chunks there are starts a new upload rather
 * than corrupting the one already staged.
 *
 * <p>Chunks are streamed to and from disk and never held whole in memory. A
 * two-gigabyte model has to survive this path, and the previous one assembled
 * it by concatenating byte arrays.
 */
@Service
public class ChunkedUploadStaging {

    private static final Logger log = LoggerFactory.getLogger(ChunkedUploadStaging.class);

    /** Copy buffer. Bounded, and the only memory an upload of any size uses. */
    private static final int BUFFER_BYTES = 64 * 1024;

    /** Records how much has been staged, so the cap is exact rather than estimated. */
    private static final String SIZE_MARKER = "staged-bytes";

    /**
     * Locks striped over a fixed array rather than one per upload.
     *
     * <p>A map of locks keyed by upload would be the obvious thing and would
     * be another unbounded store keyed by client input — the mistake this
     * class exists to correct. A fixed array cannot grow; two unrelated
     * uploads occasionally sharing a lock costs nothing.
     */
    private static final int LOCK_STRIPES = 64;
    private final Object[] locks = new Object[LOCK_STRIPES];

    private final Path stagingRoot;
    private final UploadStagingProperties limits;

    /** When the expiry sweep last ran, so it runs rarely. */
    private final AtomicLong lastSweepEpochMillis = new AtomicLong();

    public ChunkedUploadStaging(@Value("${cde.storage.upload-dir}") String uploadDir,
                                UploadStagingProperties limits) {
        this.stagingRoot = Path.of(uploadDir).resolve("staging");
        this.limits = limits;
        for (int stripe = 0; stripe < LOCK_STRIPES; stripe++) {
            locks[stripe] = new Object();
        }
    }

    /**
     * Stores one chunk and reports how many of this upload's chunks are now
     * present.
     *
     * @return the count staged so far; equal to {@code totalChunks} when the
     *         upload is complete.
     */
    public int stage(String uploadId, int chunkIndex, int totalChunks,
                     InputStream chunk, long chunkSizeBytes) throws IOException {
        requireWithinLimits(chunkIndex, totalChunks, chunkSizeBytes);
        sweepExpiredOccasionally();

        Path directory = stagingDirectoryFor(uploadId, totalChunks);

        synchronized (lockFor(directory)) {
            Files.createDirectories(directory);
            long alreadyStaged = stagedBytes(directory);

            // Re-sending a chunk replaces it, so its previous size stops
            // counting. Without this a client retrying a chunk after a network
            // failure would be charged for it twice and eventually refused.
            Path destination = directory.resolve(chunkFileName(chunkIndex));
            long replacing = Files.exists(destination) ? Files.size(destination) : 0;

            long projected = alreadyStaged - replacing + chunkSizeBytes;
            if (projected > limits.getMaxFileSize().toBytes()) {
                throw new UploadRejectedException(
                    "This upload has reached the maximum file size of "
                    + limits.getMaxFileSize().toMegabytes() + " MB.");
            }

            try (OutputStream out = Files.newOutputStream(destination)) {
                chunk.transferTo(out);
            }
            writeStagedBytes(directory, projected);

            return countStagedChunks(directory);
        }
    }

    /**
     * Streams the staged chunks, in index order, into {@code destination}, and
     * discards the staging area.
     *
     * @return the assembled size in bytes.
     */
    public long assembleInto(String uploadId, int totalChunks, Path destination)
            throws IOException {
        Path directory = stagingDirectoryFor(uploadId, totalChunks);

        synchronized (lockFor(directory)) {
            if (countStagedChunks(directory) != totalChunks) {
                throw new UploadRejectedException(
                    "The upload is not complete: " + countStagedChunks(directory)
                    + " of " + totalChunks + " chunks have arrived.");
            }

            long assembled = 0;
            Files.createDirectories(destination.getParent());

            try (OutputStream out = Files.newOutputStream(destination)) {
                byte[] buffer = new byte[BUFFER_BYTES];
                for (int index = 0; index < totalChunks; index++) {
                    Path part = directory.resolve(chunkFileName(index));
                    if (!Files.exists(part)) {
                        throw new UploadRejectedException(
                            "Chunk " + index + " is missing. Send it and try again.");
                    }
                    try (InputStream in = Files.newInputStream(part)) {
                        int read;
                        while ((read = in.read(buffer)) > 0) {
                            out.write(buffer, 0, read);
                            assembled += read;
                        }
                    }
                }
            } catch (IOException | RuntimeException failure) {
                // A half-written file must not be left where a document record
                // could come to point at it.
                Files.deleteIfExists(destination);
                throw failure;
            }

            discard(directory);
            return assembled;
        }
    }

    /** Deletes an upload's staged chunks, if any are present. */
    public void discard(String uploadId, int totalChunks) {
        discard(stagingDirectoryFor(uploadId, totalChunks));
    }

    // ── Limits ───────────────────────────────────────────────────────────────

    private void requireWithinLimits(int chunkIndex, int totalChunks, long chunkSizeBytes) {
        if (totalChunks < 1 || totalChunks > limits.getMaxChunks()) {
            throw new UploadRejectedException(
                "An upload may be split into between 1 and " + limits.getMaxChunks()
                + " chunks; this one declares " + totalChunks + ".");
        }
        if (chunkIndex < 0 || chunkIndex >= totalChunks) {
            throw new UploadRejectedException(
                "Chunk index " + chunkIndex + " is outside the declared total of "
                + totalChunks + "; indices run from 0 to " + (totalChunks - 1) + ".");
        }
        if (chunkSizeBytes > limits.getMaxChunkSize().toBytes()) {
            throw new UploadRejectedException(
                "A single chunk may be at most " + limits.getMaxChunkSize().toMegabytes()
                + " MB.");
        }
    }

    // ── Staging layout ───────────────────────────────────────────────────────

    /**
     * The directory holding one tenant's chunks for one upload.
     *
     * <p>The client's identifier is hashed rather than used, so nothing it
     * contains — separators, dots, control characters — can influence where the
     * chunks land. There is no sanitising step to get wrong.
     */
    private Path stagingDirectoryFor(String uploadId, int totalChunks) {
        long tenantId = TenantContext.requireTenantId();
        String material = tenantId + ":" + uploadId + ":" + totalChunks;

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
            return stagingRoot.resolve(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required and unavailable", e);
        }
    }

    private static String chunkFileName(int chunkIndex) {
        // Fixed width so the directory sorts the way the file assembles, which
        // makes the staging area readable when something has gone wrong.
        return "%08d".formatted(chunkIndex);
    }

    private Object lockFor(Path directory) {
        return locks[Math.floorMod(directory.getFileName().toString().hashCode(), LOCK_STRIPES)];
    }

    private int countStagedChunks(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return (int) entries.filter(path -> !path.getFileName().toString().equals(SIZE_MARKER))
                                .count();
        }
    }

    private long stagedBytes(Path directory) throws IOException {
        Path marker = directory.resolve(SIZE_MARKER);
        if (!Files.exists(marker)) {
            return 0;
        }
        try {
            return Long.parseLong(Files.readString(marker, StandardCharsets.UTF_8).trim());
        } catch (NumberFormatException unreadable) {
            // Recomputed rather than trusted. A corrupt marker must not read as
            // zero, which would let the cap be bypassed by damaging one file.
            try (Stream<Path> entries = Files.list(directory)) {
                return entries.filter(path -> !path.getFileName().toString().equals(SIZE_MARKER))
                              .mapToLong(ChunkedUploadStaging::sizeOf).sum();
            }
        }
    }

    private void writeStagedBytes(Path directory, long bytes) throws IOException {
        Files.writeString(directory.resolve(SIZE_MARKER), Long.toString(bytes),
                          StandardCharsets.UTF_8);
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }

    private void discard(Path directory) {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> entries = Files.list(directory)) {
            entries.forEach(entry -> {
                try {
                    Files.deleteIfExists(entry);
                } catch (IOException e) {
                    log.warn("Could not delete a staged chunk", e);
                }
            });
            Files.deleteIfExists(directory);
        } catch (IOException e) {
            log.warn("Could not clear a staging directory", e);
        }
    }

    // ── Expiry ───────────────────────────────────────────────────────────────

    /**
     * Deletes staging directories nothing has touched for the configured
     * period, at most once per expiry interval per instance.
     *
     * <p>Opportunistic rather than scheduled, deliberately. A scheduled job
     * here would need a distributed lock to stop every replica sweeping at
     * once, and would be one more thing that can silently stop running; this
     * cannot, because the only thing that creates work for it is the same
     * request path that triggers it.
     */
    private void sweepExpiredOccasionally() {
        long now = System.currentTimeMillis();
        long last = lastSweepEpochMillis.get();
        Duration expiry = limits.getStagingExpiry();

        if (now - last < expiry.toMillis()) {
            return;
        }
        if (!lastSweepEpochMillis.compareAndSet(last, now)) {
            return;
        }
        if (!Files.isDirectory(stagingRoot)) {
            return;
        }

        Instant cutoff = Instant.ofEpochMilli(now).minus(expiry);
        try (Stream<Path> uploads = Files.list(stagingRoot)) {
            uploads.filter(Files::isDirectory)
                   .filter(directory -> lastModified(directory).isBefore(cutoff))
                   .forEach(this::discard);
        } catch (IOException e) {
            // Nothing the caller can act on: their chunk was stored, and the
            // sweep will be attempted again.
            log.warn("Could not sweep expired upload staging", e);
        }
    }

    private Instant lastModified(Path directory) {
        try {
            return Files.getLastModifiedTime(directory).toInstant();
        } catch (IOException e) {
            return Instant.now();
        }
    }

    /** Exposed so the direct-upload path shares one definition of "too large". */
    public long maxFileSizeBytes() {
        return limits.getMaxFileSize().toBytes();
    }

    /** Copies a stream to a destination without holding it in memory. */
    public long streamTo(InputStream source, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        try {
            return Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(destination);
            throw failure;
        }
    }
}
