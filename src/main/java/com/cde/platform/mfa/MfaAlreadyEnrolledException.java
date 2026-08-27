package com.cde.platform.mfa;

/**
 * The user already has a confirmed second factor.
 *
 * <p>Refused rather than silently replaced: quietly issuing a new secret would
 * let anyone with a live session swap out the second factor, which is exactly
 * the escalation the factor exists to prevent. Replacing one is a separate,
 * step-up-protected operation.
 */
public class MfaAlreadyEnrolledException extends RuntimeException {

    public MfaAlreadyEnrolledException(long userId) {
        super("User " + userId + " already has a confirmed second factor");
    }
}
