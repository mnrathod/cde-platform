package com.cde.platform.security;

import java.util.Set;

/**
 * The permissions that gate markup on a document — annotations, their reply
 * threads, and the XFDF exchange of both.
 *
 * <p>Separate from {@link DocumentPermission} because commenting on a drawing
 * and altering one are different acts with different audiences. Review is the
 * job a reviewer is for: they must be able to raise a comment and answer one
 * without thereby being able to redact the document or delete it. Equally, a
 * viewer who may read a drawing has no business writing on it, and before this
 * vocabulary existed they could — nothing checked.
 *
 * <p>The authority values are {@code annotation:*} rather than {@code
 * markup:*}, because that is what the published specification already tells
 * callers to expect. The class is named for the domain concept; the strings
 * are the contract, and the contract came first.
 */
public final class MarkupPermission {

    private MarkupPermission() {
    }

    /** Read a document's annotations and their replies, and export them as XFDF. */
    public static final String READ = "annotation:read";

    /**
     * Create, edit, resolve and delete annotations and replies, and import
     * XFDF.
     *
     * <p>One permission rather than one per verb. Raising a comment and
     * answering it are the same activity from the same person, and an import
     * is a batch of creations; splitting them would produce settings with no
     * answerable question behind them (§1.1).
     */
    public static final String WRITE = "annotation:write";

    public static final Set<String> ALL = Set.of(READ, WRITE);
}
