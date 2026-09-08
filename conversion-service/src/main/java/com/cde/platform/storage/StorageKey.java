package com.cde.platform.storage;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Where an object lives, in a form that cannot name someone else's object.
 *
 * <p>The layout is {@code {environment}/{tenantId}/{category}/{objectId}} on
 * every backend, so the same key resolves to a blob path, an S3 key, a GCS
 * object name, or a filesystem path without the caller knowing which.
 *
 * <p>This is a type rather than a string for one reason: <strong>a key cannot
 * be constructed without a tenant.</strong> Tenant-prefixing files is the kind
 * of rule that holds until the one call site that forgets, and a forgotten
 * prefix in storage is a cross-tenant leak — the same severity as a missing
 * {@code WHERE tenant_id}. Making the prefix part of the type means the
 * compiler asks the question at every call site instead of a reviewer asking
 * it at some of them.
 *
 * <p>Path traversal is impossible by construction rather than by sanitisation.
 * {@code objectId} must match a strict pattern — no separators, no dots except
 * one before an extension, no {@code ..} — and the pattern is an allow-list, so
 * a traversal attempt is rejected because it is not in the permitted shape,
 * not because it matched a list of known-bad sequences.
 */
public record StorageKey(
    String environment,
    long tenantId,
    StorageCategory category,
    String objectId) {

    /**
     * A server-generated identifier and, optionally, a short alphanumeric
     * extension. Deliberately narrow: this is what
     * {@code StoredFileName.forStorage} produces, and nothing else needs to be
     * expressible.
     */
    private static final Pattern PERMITTED_OBJECT_ID =
        Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}(\\.[A-Za-z0-9]{1,8})?");

    /** Environment names name a deployment, so the same shape is enough. */
    private static final Pattern PERMITTED_ENVIRONMENT =
        Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");

    public StorageKey {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(objectId, "objectId");

        if (!PERMITTED_ENVIRONMENT.matcher(environment).matches()) {
            throw new IllegalArgumentException(
                "Environment must be lowercase letters, digits and hyphens: " + environment);
        }
        if (tenantId <= 0) {
            // Zero and negative are the values a missing tenant takes when
            // someone reaches for a default, so they are refused explicitly
            // rather than producing a plausible-looking shared prefix.
            throw new IllegalArgumentException(
                "Storage keys require a real tenant; got " + tenantId);
        }
        if (!PERMITTED_OBJECT_ID.matcher(objectId).matches()) {
            throw new IllegalArgumentException(
                "Object id must be a server-generated identifier with an optional "
                + "short extension, and must contain no path separators: " + objectId);
        }
    }

    /**
     * The key as a backend-neutral path. Always relative, always forward
     * slashes, never leading or trailing.
     */
    public String path() {
        return environment + '/' + tenantId + '/' + category.segment() + '/' + objectId;
    }

    /** The prefix covering everything one tenant owns in one category. */
    public String categoryPrefix() {
        return environment + '/' + tenantId + '/' + category.segment() + '/';
    }

    /** The prefix covering everything one tenant owns. */
    public String tenantPrefix() {
        return environment + '/' + tenantId + '/';
    }

    @Override
    public String toString() {
        return path();
    }
}
