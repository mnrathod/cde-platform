package com.cde.platform.storage;

/**
 * What a completed write produced.
 *
 * <p>Carries the digest so the caller can record it without re-reading the
 * object. Integrity checking later — the CDE archive verifies stored content
 * on a schedule — needs a value recorded at write time to compare against, and
 * computing it during the write that is already streaming the bytes is free.
 */
public record StorageObjectRef(
    StorageKey key,
    long sizeBytes,
    String sha256) {

    public StorageObjectRef {
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("A completed write has a known size");
        }
    }
}
