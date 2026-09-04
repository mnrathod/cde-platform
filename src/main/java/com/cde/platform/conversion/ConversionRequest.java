package com.cde.platform.conversion;

import java.net.URI;
import java.util.UUID;

/**
 * One queued conversion, including the credential that must not be written
 * down.
 *
 * <p>The {@code sourceUrl} is a presigned link — an S3 GET, an Azure SAS, a
 * Graph download URL — and therefore a bearer credential: whoever holds it has
 * the access. It exists here, in the queue, in memory, and nowhere else. It is
 * not in the database (see {@code V7__conversion_jobs.sql}), not in a broker
 * journal, and it must not be logged: {@link #toString()} is overridden so
 * that a debug statement, an exception message, or a collection dump cannot
 * print it by accident.
 *
 * <p><strong>This is why the queue is in-process rather than Artemis</strong>,
 * which §7.8 would otherwise make the default for document processing. A
 * message carrying this URL would put the credential in the broker's journal
 * on disk, which is the same objection that keeps it out of the database. The
 * alternatives were to encrypt it into a message — a key, a rotation story and
 * a test suite, to protect something with a fifteen-minute life — or to hold
 * it in memory and accept that a job interrupted by a restart is failed rather
 * than resumed. The second is smaller and its failure mode is honest, so that
 * is what this is.
 *
 * <p>The cost is real and should be understood: execution is bound to the
 * instance that accepted the submission, which is a deviation from §8.1. Job
 * <em>status</em> is not — that lives in the database and any instance serves
 * it — so what is lost is only the ability to resume in-flight work elsewhere,
 * which the un-persisted credential rules out anyway.
 */
public record ConversionRequest(long tenantId, UUID jobPublicId, URI sourceUrl) {

    /**
     * Deliberately omits the URL.
     *
     * <p>Records generate a {@code toString} that prints every component, and
     * this one holds a credential. The commonest way a secret reaches a log is
     * not a deliberate statement about it — it is an object that happened to
     * be interpolated into one.
     */
    @Override
    public String toString() {
        return "ConversionRequest[tenantId=" + tenantId + ", jobPublicId=" + jobPublicId
             + ", sourceUrl=(withheld)]";
    }
}
