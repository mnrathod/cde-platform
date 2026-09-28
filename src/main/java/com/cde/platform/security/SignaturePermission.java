package com.cde.platform.security;

import java.util.Set;

/**
 * The permissions that gate digital signatures on a document.
 *
 * <p>Signing is an authorisation act, not an editing one: it asserts that a
 * named person stands behind this revision. That is why it has its own
 * vocabulary rather than sitting under {@link DocumentPermission#WRITE} — the
 * authority to upload a drawing and the authority to certify one belong to
 * different people, and a product that conflates them cannot represent a
 * sign-off at all.
 *
 * <p>Revoking sits with signing rather than with reading for the same reason.
 * Withdrawing a certification is an exercise of the same authority that gave
 * it.
 */
public final class SignaturePermission {

    private SignaturePermission() {
    }

    /** List a document's signatures and verify one. */
    public static final String READ = "signature:read";

    /** Sign a document, and revoke a signature. */
    public static final String WRITE = "signature:write";

    public static final Set<String> ALL = Set.of(READ, WRITE);
}
