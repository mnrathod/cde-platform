package com.cde.platform.security;

import java.util.Set;

/**
 * The permissions that gate the projects documents live in.
 *
 * <p>A project is the boundary that documents, containers and permissions are
 * all scoped by, so deleting one is among the most consequential operations
 * the product offers — and until these existed, any authenticated caller could
 * do it, including one whose only granted permission was read access.
 */
public final class ProjectPermission {

    private ProjectPermission() {
    }

    /** List projects and read one. */
    public static final String READ = "project:read";

    /**
     * Create a project, rename it, and delete it.
     *
     * <p>Held by the roles that originate information rather than by
     * administrators alone. Requiring an administrator to create every project
     * would make the common case of starting a new job an access request, and
     * §1.2's ten-minute first workflow could not survive it.
     */
    public static final String WRITE = "project:write";

    public static final Set<String> ALL = Set.of(READ, WRITE);
}
