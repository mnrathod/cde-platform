package com.cde.platform.storage.local;

import com.cde.platform.storage.MultipartUpload;
import com.cde.platform.storage.ObjectNotFoundException;
import com.cde.platform.storage.StorageCategory;
import com.cde.platform.storage.StorageException;
import com.cde.platform.storage.StorageKey;
import com.cde.platform.storage.StorageMetadata;
import com.cde.platform.storage.StorageObjectRef;
import com.cde.platform.storage.StorageOperation;
import com.cde.platform.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Object storage on a filesystem, for on-premises and air-gapped deployments.
 *
 * <p>This is not a development stand-in. Air-gapped installations are a
 * first-class deliverable and most of the sovereign and Defence scope, and
 * they have no object store to talk to — so this provider carries the same
 * obligations as the cloud ones and runs the same contract test suite.
 *
 * <h2>Encryption</h2>
 * Encryption at rest is the volume's job here, not this class's. That is a
 * real difference from the cloud providers, which encrypt server-side per
 * object, and it means an operator who mounts an unencrypted volume gets
 * unencrypted customer data with nothing in the application to stop them. It
 * is stated in the deployment documentation rather than silently assumed.
 */
public class LocalFilesystemStorageProvider implements StorageProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalFilesystemStorageProvider.class);

    /** 64 KB: large enough to keep syscall overhead down, small enough to stay off the heap's radar. */
    private static final int TRANSFER_BUFFER_BYTES = 64 * 1024;

    private final Path root;
    private final SignedObjectToken tokens;
    private final URI downloadBaseUri;

    public LocalFilesystemStorageProvider(Path root, SignedObjectToken tokens, URI downloadBaseUri) {
        this.root = root.toAbsolutePath().normalize();
        this.tokens = tokens;
        this.downloadBaseUri = downloadBaseUri;
    }

    @Override
    public StorageObjectRef store(StorageKey key, InputStream content, StorageMetadata metadata) {
        Path destination = resolve(key);
        // Write to a sibling temporary file and move it into place. A reader
        // arriving mid-write would otherwise see a partial object, and on this
        // backend a partial object is indistinguishable from a complete one —
        // there is no upload-id to tell them apart.
        Path partial = destination.resolveSibling(destination.getFileName() + ".partial");

        try {
            Files.createDirectories(destination.getParent());

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long written;
            try (OutputStream out = Files.newOutputStream(partial);
                 DigestOutputStream digesting = new DigestOutputStream(out, digest)) {
                written = transfer(content, digesting);
            }

            Files.move(partial, destination,
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            return new StorageObjectRef(key, written,
                HexFormat.of().formatHex(digest.digest()));

        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        } catch (IOException failure) {
            discardQuietly(partial);
            throw new StorageException("Could not write " + key.path(), failure);
        }
    }

    @Override
    public InputStream retrieve(StorageKey key) {
        Path source = resolve(key);
        if (!Files.isRegularFile(source)) {
            throw new ObjectNotFoundException(key);
        }
        try {
            return Files.newInputStream(source);
        } catch (IOException failure) {
            throw new StorageException("Could not read " + key.path(), failure);
        }
    }

    @Override
    public URI presignedUrl(StorageKey key, Duration validFor, StorageOperation operation) {
        // Capped rather than rejected: a caller asking for an hour gets fifteen
        // minutes, because failing the request would push callers toward not
        // using presigned URLs at all.
        Duration capped = validFor.compareTo(MAX_PRESIGNED_VALIDITY) > 0
            ? MAX_PRESIGNED_VALIDITY
            : validFor;

        String token = tokens.issue(key, operation, Instant.now().plus(capped));
        return downloadBaseUri.resolve(
            "/api/storage/" + key.path() + "?operation="
            + operation.name().toLowerCase(java.util.Locale.ROOT) + "&token=" + token);
    }

    @Override
    public void delete(StorageKey key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException failure) {
            throw new StorageException("Could not delete " + key.path(), failure);
        }
    }

    @Override
    public boolean exists(StorageKey key) {
        return Files.isRegularFile(resolve(key));
    }

    @Override
    public Optional<StorageMetadata> metadataOf(StorageKey key) {
        Path source = resolve(key);
        if (!Files.isRegularFile(source)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new StorageMetadata(
                Files.probeContentType(source),
                Files.size(source),
                key.objectId(),
                Files.getLastModifiedTime(source).toInstant(),
                java.util.Map.of()));
        } catch (IOException failure) {
            throw new StorageException("Could not read metadata for " + key.path(), failure);
        }
    }

    @Override
    public List<StorageKey> listCategory(StorageKey keyInCategory) {
        Path directory = root.resolve(keyInCategory.categoryPrefix()).normalize();
        if (!Files.isDirectory(directory)) {
            return List.of();
        }

        List<StorageKey> found = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                // Partial writes are not objects. Returning one would hand a
                // caller a key whose content is still being written.
                if (Files.isRegularFile(entry) && !name.endsWith(".partial")) {
                    found.add(new StorageKey(keyInCategory.environment(), keyInCategory.tenantId(),
                        keyInCategory.category(), name));
                }
            }
        } catch (IOException failure) {
            throw new StorageException("Could not list " + keyInCategory.categoryPrefix(), failure);
        }
        found.sort(java.util.Comparator.comparing(StorageKey::objectId));
        return List.copyOf(found);
    }

    @Override
    public void copy(StorageKey source, StorageKey destination) {
        Path from = resolve(source);
        if (!Files.isRegularFile(from)) {
            throw new ObjectNotFoundException(source);
        }
        Path to = resolve(destination);
        try {
            Files.createDirectories(to.getParent());
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            throw new StorageException(
                "Could not copy " + source.path() + " to " + destination.path(), failure);
        }
    }

    @Override
    public void move(StorageKey source, StorageKey destination) {
        // Copy then delete rather than rename, so a failure part-way leaves the
        // source intact. A rename that fails across a mount boundary would
        // otherwise lose the object entirely.
        copy(source, destination);
        delete(source);
    }

    @Override
    public MultipartUpload initiateMultipartUpload(StorageKey key, StorageMetadata metadata) {
        return LocalMultipartUpload.begin(key, resolve(key));
    }

    @Override
    public StorageObjectRef completeMultipartUpload(MultipartUpload upload) {
        if (!(upload instanceof LocalMultipartUpload local)) {
            throw new StorageException(
                "This provider cannot complete an upload it did not start: "
                + upload.getClass().getSimpleName());
        }
        return local.complete();
    }

    @Override
    public void abortMultipartUpload(MultipartUpload upload) {
        if (upload instanceof LocalMultipartUpload local) {
            local.abort();
        }
    }

    @Override
    public String providerName() {
        return "local";
    }

    @Override
    public void verifyReachable() {
        // Writes and reads back a real file. Checking that the directory exists
        // would pass on a read-only mount, a full volume, and a path owned by
        // another user — three configurations that start cleanly and fail on
        // the first upload.
        Path probe = root.resolve(".storage-self-test");
        try {
            Files.createDirectories(root);
            Files.writeString(probe, Long.toString(System.nanoTime()));
            Files.readString(probe);
            Files.deleteIfExists(probe);
        } catch (IOException failure) {
            throw new StorageException(
                "Storage root " + root + " is not writable: " + failure.getMessage(), failure);
        }
        log.info("Local storage self-test passed at {}", root);
    }

    /**
     * Turns a key into a path under the root, and refuses anything that would
     * land outside it.
     *
     * <p>{@link StorageKey} already makes traversal unrepresentable, so this
     * check should be unreachable. It stays because it is the last line before
     * the filesystem, it costs a normalise and a comparison, and "unreachable"
     * is a property of today's key validation rather than of this method.
     */
    private Path resolve(StorageKey key) {
        Path resolved = root.resolve(key.path()).normalize();
        if (!resolved.startsWith(root)) {
            throw new StorageException("Key resolved outside the storage root: " + key.path());
        }
        return resolved;
    }

    /** Streams with a bounded buffer. Never accumulates the content. */
    static long transfer(InputStream from, OutputStream to) throws IOException {
        byte[] buffer = new byte[TRANSFER_BUFFER_BYTES];
        long total = 0;
        int read;
        while ((read = from.read(buffer)) != -1) {
            to.write(buffer, 0, read);
            total += read;
        }
        return total;
    }

    private static void discardQuietly(Path partial) {
        try {
            Files.deleteIfExists(partial);
        } catch (IOException ignored) {
            // The write already failed and that is what the caller is being
            // told about. A leftover .partial is excluded from listings and
            // swept by the temp-file cleanup; masking the real failure with
            // this one would help nobody.
            log.warn("Could not remove the partial file at {}", partial);
        }
    }

    /** For diagnostics and the health endpoint. */
    public Path root() {
        return root;
    }

    /** Every category a tenant could hold, for offboarding and quota reporting. */
    public static List<StorageCategory> categories() {
        return List.of(StorageCategory.values());
    }
}
