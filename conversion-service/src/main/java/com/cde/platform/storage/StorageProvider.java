package com.cde.platform.storage;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Object storage, in the one shape feature code is allowed to see.
 *
 * <p>No caller knows which backend is active. That is what makes a single
 * artifact deployable to Azure, AWS, GCP and an air-gapped rack without a
 * code change, and it is why every cloud-specific type is kept out of this
 * interface — an {@code S3Client} appearing in a signature here would make the
 * abstraction decorative.
 *
 * <h2>Streaming, not buffers</h2>
 * Every method that moves content takes or returns an {@link InputStream}.
 * None takes or returns a {@code byte[]}. A 2 GB model upload must never
 * touch application heap, and the surest way to guarantee that is to give
 * callers no method that would let them.
 *
 * <h2>Keys carry their tenant</h2>
 * {@link StorageKey} cannot be constructed without a tenant, so there is no
 * call site here where the prefix can be forgotten. Path traversal is
 * impossible for the same reason: object identifiers are server-generated and
 * validated against an allow-list before a key exists at all.
 *
 * <h2>Failure</h2>
 * Implementations throw {@link StorageException} for transport and backend
 * failures, and {@link ObjectNotFoundException} for a key that is not there.
 * Neither carries a provider-specific exception type across the boundary,
 * because a caller that catches {@code AmazonS3Exception} has quietly bound
 * itself to a provider.
 */
public interface StorageProvider {

    /**
     * Streams content to {@code key} and returns a reference to what was
     * written. Overwrites: keys are server-generated, so a collision means a
     * caller has reused an identifier deliberately.
     */
    StorageObjectRef store(StorageKey key, InputStream content, StorageMetadata metadata);

    /**
     * Opens the object for reading. The caller closes the stream.
     *
     * @throws ObjectNotFoundException if the key holds nothing
     */
    InputStream retrieve(StorageKey key);

    /**
     * A short-lived URL granting one operation on one object.
     *
     * <p>Capped at {@link #MAX_PRESIGNED_VALIDITY} regardless of what is asked
     * for. A presigned URL is a bearer credential: whoever holds it has the
     * access, so it is scoped to a single object and a single operation, and
     * expires quickly enough that a leaked one is worth little.
     */
    URI presignedUrl(StorageKey key, Duration validFor, StorageOperation operation);

    /** Longest a presigned URL may live, whatever the caller requests. */
    Duration MAX_PRESIGNED_VALIDITY = Duration.ofMinutes(15);

    /** Removes the object. Idempotent: deleting what is not there succeeds. */
    void delete(StorageKey key);

    boolean exists(StorageKey key);

    /** Metadata without transferring content. Empty if the key holds nothing. */
    Optional<StorageMetadata> metadataOf(StorageKey key);

    /**
     * Every key under one tenant-and-category prefix.
     *
     * <p>Takes a {@link StorageKey} rather than a raw prefix string so that a
     * listing cannot be widened past a tenant boundary by passing a shorter
     * string — the commonest way a list operation turns into a cross-tenant
     * read.
     */
    List<StorageKey> listCategory(StorageKey keyInCategory);

    /** Server-side copy where the backend supports one; streamed where not. */
    void copy(StorageKey source, StorageKey destination);

    /** Copy then delete, so a failure part-way leaves the source intact. */
    void move(StorageKey source, StorageKey destination);

    /**
     * Begins a chunked upload for content too large to send in one request.
     * Parts are appended through the returned handle and are not visible at
     * {@code key} until the upload completes.
     */
    MultipartUpload initiateMultipartUpload(StorageKey key, StorageMetadata metadata);

    /** Assembles the parts and publishes the object at its key. */
    StorageObjectRef completeMultipartUpload(MultipartUpload upload);

    /** Discards an incomplete upload and its parts. */
    void abortMultipartUpload(MultipartUpload upload);

    /** Which backend this is, for logs, metrics and the health endpoint. */
    String providerName();

    /**
     * Proves the backend is actually reachable and writable.
     *
     * <p>Called at startup and by the readiness probe. Configuration that
     * parses is not configuration that works: a wrong bucket name, an expired
     * credential and a missing mount all produce a service that starts
     * cleanly and fails on the first upload. This turns that into a instance
     * that never joins the load balancer.
     *
     * @throws StorageException if the backend cannot be reached or written to
     */
    void verifyReachable();
}
