package com.cde.platform.audit;

/**
 * Whether the audited action took effect.
 *
 * <p>Three values rather than a boolean: a refusal on authority and a failure
 * on everything else are different events to whoever reads the trail. A burst
 * of {@link #DENIED} is somebody probing; a burst of {@link #FAILURE} is
 * usually something broken.
 */
public enum AuditOutcome {

    SUCCESS,

    /** Attempted and did not take effect — bad credentials, invalid input. */
    FAILURE,

    /** Refused on authority: authenticated, but not permitted. */
    DENIED
}
