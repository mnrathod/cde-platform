package com.cde.platform.storage.local;

import com.cde.platform.storage.MultipartUpload;
import com.cde.platform.storage.StorageException;
import com.cde.platform.storage.StorageKey;
import com.cde.platform.storage.StorageObjectRef;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A chunked upload appended to a single temporary file.
 *
 * <p>Parts are appended in call order rather than numbered and reassembled.
 * Numbered parts exist in the cloud APIs so that parts can be uploaded in
 * parallel from different machines; here there is one process writing to one
 * local file, so ordering is already guaranteed and part numbering would add
 * bookkeeping and a class of ordering bug in exchange for nothing.
 *
 * <p>The object appears at its key only on {@link #complete()}. Before that
 * the bytes live under a {@code .partial} name that listings skip, so no
 * caller can be handed a key whose content is still arriving — which matters
 * because a half-written file is indistinguishable from a complete one on this
 * backend, and a scanner asked to check one would report the fragment clean.
 */
final class LocalMultipartUpload implements MultipartUpload {

    private final StorageKey key;
    private final Path destination;
    private final Path partial;
    private final MessageDigest digest;

    private long bytesWritten;
    private boolean finished;

    private LocalMultipartUpload(StorageKey key, Path destination, Path partial, MessageDigest digest) {
        this.key = key;
        this.destination = destination;
        this.partial = partial;
        this.digest = digest;
    }

    static LocalMultipartUpload begin(StorageKey key, Path destination) {
        Path partial = destination.resolveSibling(destination.getFileName() + ".partial");
        try {
            Files.createDirectories(destination.getParent());
            // Truncate: an abandoned upload to the same key must not have its
            // bytes silently prefixed to this one.
            Files.deleteIfExists(partial);
            Files.createFile(partial);
            return new LocalMultipartUpload(key, destination, partial,
                MessageDigest.getInstance("SHA-256"));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        } catch (IOException failure) {
            throw new StorageException("Could not begin an upload for " + key.path(), failure);
        }
    }

    @Override
    public StorageKey key() {
        return key;
    }

    @Override
    public void appendPart(InputStream part) {
        if (finished) {
            throw new StorageException("Upload for " + key.path() + " is already finished");
        }
        try (OutputStream out = Files.newOutputStream(partial, StandardOpenOption.APPEND);
             DigestOutputStream digesting = new DigestOutputStream(out, digest)) {
            bytesWritten += LocalFilesystemStorageProvider.transfer(part, digesting);
        } catch (IOException failure) {
            throw new StorageException("Could not append a part to " + key.path(), failure);
        }
    }

    StorageObjectRef complete() {
        if (finished) {
            throw new StorageException("Upload for " + key.path() + " is already finished");
        }
        try {
            Files.move(partial, destination,
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            finished = true;
            return new StorageObjectRef(key, bytesWritten,
                HexFormat.of().formatHex(digest.digest()));
        } catch (IOException failure) {
            throw new StorageException("Could not publish " + key.path(), failure);
        }
    }

    void abort() {
        finished = true;
        try {
            Files.deleteIfExists(partial);
        } catch (IOException failure) {
            throw new StorageException("Could not discard the upload for " + key.path(), failure);
        }
    }

    @Override
    public long bytesWritten() {
        return bytesWritten;
    }
}
