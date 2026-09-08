package com.cde.platform.conversion;

import java.util.List;
import java.util.function.Supplier;

/**
 * Who is asking for a conversion, as the conversion service needs to understand
 * it.
 *
 * <p>This interface exists so that the conversion service does not know what a
 * tenant is. It knows there are callers, that work belongs to one of them, and
 * that one caller's backlog must not starve another's — and nothing else. In
 * this platform a caller happens to be a {@code Tenant}; in a deployment where
 * the conversion service is shipped on its own (ADR 12) a caller is whichever
 * client authenticated, and no tenant table exists at all.
 *
 * <p><strong>The identifier is opaque and the service treats it that way.</strong>
 * {@code ConversionWorkQueue} uses it as a round-robin partition key,
 * {@code StorageKey} uses it as a prefix, and job visibility uses it as a
 * filter. None of those need it to mean anything, which is exactly why the
 * conversion service can be extracted without first solving the viewer's
 * identity problem: <em>conversion needs to know which client is calling, not
 * which person.</em> Those are different problems with different answers, and
 * conflating them is what would make this extraction wait on a decision it does
 * not depend on.
 *
 * <p>The submitter is separate from the caller on purpose. This platform
 * records a user id against each job because it has users; a standalone
 * deployment authenticating a machine client can return the caller id for both
 * and lose nothing. Collapsing them here would remove attribution the platform
 * already has.
 */
public interface ConversionCallers {

    /**
     * The caller the current request belongs to.
     *
     * @throws IllegalStateException if the request arrived without one — a
     *         conversion that belongs to nobody cannot be partitioned, stored
     *         or made visible, so failing is the only honest option
     */
    long requireCurrentCallerId();

    /**
     * The identifier to record as having submitted the job.
     *
     * @param username the authenticated principal's name
     * @throws IllegalStateException if the name resolves to nobody
     */
    long requireSubmitterId(String username);

    /**
     * Runs work as though {@code callerId} had asked for it.
     *
     * <p>Needed by background work that has no request to inherit context from
     * — startup recovery is the only current caller, and it runs before any
     * request exists.
     */
    <T> T callAsCaller(long callerId, Supplier<T> work);

    /**
     * The same, for work that returns nothing.
     *
     * <p>Most of the pipeline's context switches are progress writes, and
     * expressing those through the {@link Supplier} form means a
     * {@code return null} on every one — noise that reads as though a value
     * were being discarded.
     */
    void runAsCaller(long callerId, Runnable work);

    /**
     * Every caller this deployment knows about.
     *
     * <p>Only startup recovery needs this, to find jobs a restart interrupted.
     * A standalone deployment with no caller registry may return the callers it
     * has seen, or an empty list and accept that interrupted jobs stay
     * interrupted until someone asks about them.
     */
    List<Long> knownCallerIds();
}
