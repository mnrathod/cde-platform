package com.cde.platform.storage;

import java.io.InputStream;

/**
 * An upload in progress, assembled from parts.
 *
 * <p>Nothing is visible at the key until {@link StorageProvider#completeMultipartUpload}
 * succeeds. That matters beyond tidiness: a half-written document that became
 * readable would be served to users, scanned as if complete, and could be
 * promoted out of quarantine on the strength of a scan that only saw a
 * fragment.
 *
 * <p>Implementations close over whatever their backend needs — an S3 upload id,
 * an open file handle — and callers see only this.
 */
public interface MultipartUpload {

    /** The key this upload will publish to when it completes. */
    StorageKey key();

    /**
     * Appends one part. Parts are appended in call order; there is no part
     * numbering to get wrong.
     *
     * @throws StorageException if the part cannot be written
     */
    void appendPart(InputStream part);

    /** Bytes accepted so far, for progress reporting and quota enforcement. */
    long bytesWritten();
}
