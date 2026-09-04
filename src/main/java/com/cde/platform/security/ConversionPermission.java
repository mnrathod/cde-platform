package com.cde.platform.security;

import java.util.Set;

/**
 * The permission that gates converting a document supplied by an integrator.
 *
 * <p>A separate vocabulary rather than a reuse of {@code container:write},
 * because the two grant different things to different callers. ADR 12's
 * integration is a machine client belonging to the host CDE: it submits links
 * and collects PDFs, and it has no business creating information containers,
 * moving them through the ISO 19650 state machine, or reading the ones that
 * already exist. Folding conversion into an existing permission would mean
 * every integration credential carried authority over the contractual record
 * as the price of converting a drawing.
 */
public final class ConversionPermission {

    private ConversionPermission() {
    }

    /**
     * Submit a conversion, read the status of one, cancel it, and download the
     * result.
     *
     * <p>One permission rather than four. The four are not separable in
     * practice — a caller who may submit must be able to find out what
     * happened and collect the output, or the submission was pointless — and
     * splitting a capability that is only ever granted whole produces settings
     * nobody can answer questions about (§1.1).
     *
     * <p>It does bound something real, though: this permission reaches only
     * the caller's own tenant's jobs, and grants nothing over documents,
     * containers or users.
     */
    public static final String SUBMIT = "document:convert";

    public static final Set<String> ALL = Set.of(SUBMIT);
}
