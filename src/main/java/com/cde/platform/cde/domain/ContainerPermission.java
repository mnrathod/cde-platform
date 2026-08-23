package com.cde.platform.cde.domain;

import java.util.Set;

/**
 * The permissions that gate the information container lifecycle.
 *
 * <p>Fine-grained verbs on a resource rather than roles, because the roles that
 * hold them differ per organisation while the operations do not. A reviewer at
 * one appointed party publishes; at another only the lead does. Naming the
 * operation lets the mapping move without every call site moving with it.
 *
 * <p>Framework-free on purpose. These names are domain vocabulary — the state
 * machine in {@link ContainerState} decides which one a given transition needs,
 * and it must stay free of Spring for the fast unit tests to keep working. The
 * mapping from role to permission lives in the security package, which is the
 * one place that knows the whole platform's vocabulary.
 */
public final class ContainerPermission {

    private ContainerPermission() {
    }

    /** Read containers, revisions and transition history. */
    public static final String READ = "container:read";

    /** Create a container, and issue a new revision of one. */
    public static final String WRITE = "container:write";

    /** Issue a revision for coordination by other task teams. */
    public static final String SHARE = "container:share";

    /** Authorise a revision for use. This produces the contractual record. */
    public static final String PUBLISH = "container:publish";

    /** Return a shared revision to its author for rework. */
    public static final String REJECT = "container:reject";

    /** Retire a revision to the historical record. */
    public static final String ARCHIVE = "container:archive";

    /**
     * Every permission this module defines.
     *
     * <p>Used to assert that the authority named in each {@code @PreAuthorize}
     * expression is one that exists. A misspelled authority fails closed — no
     * caller ever holds it — so the endpoint would simply refuse everyone,
     * which is safe but presents as a permissions bug with no obvious cause.
     */
    public static final Set<String> ALL = Set.of(READ, WRITE, SHARE, PUBLISH, REJECT, ARCHIVE);
}
