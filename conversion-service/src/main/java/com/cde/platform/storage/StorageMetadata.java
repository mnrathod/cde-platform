package com.cde.platform.storage;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * What is known about an object besides its bytes.
 *
 * <p>{@code displayName} is the only field carrying anything the client
 * supplied, and it is metadata rather than addressing — the object is found by
 * its key, which is server-generated. Keeping the client's name here rather
 * than in the key is what makes path traversal a non-question.
 *
 * <p>{@code sizeBytes} may be {@code -1} before a streaming write completes,
 * because the point of streaming is not knowing the length up front.
 */
public record StorageMetadata(
    String contentType,
    long sizeBytes,
    String displayName,
    Instant lastModified,
    Map<String, String> attributes) {

    public StorageMetadata {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /** Metadata for a write, where size and timestamp are not yet known. */
    public static StorageMetadata forUpload(String contentType, String displayName) {
        return new StorageMetadata(contentType, -1, displayName, null, Map.of());
    }

    public Optional<Instant> lastModifiedAt() {
        return Optional.ofNullable(lastModified);
    }
}
