package com.cde.platform.storage;

/**
 * What kind of thing an object is, which decides how it is treated.
 *
 * <p>A closed set rather than a free-text segment: the category appears in
 * every key, and a free-text one would let a caller invent a category that
 * no lifecycle rule, retention policy or access check knows about — an object
 * nothing governs. Adding a category should be a deliberate edit here, with
 * the retention and encryption questions answered at the same time.
 */
public enum StorageCategory {

    /** Uploaded originals, exactly as received once they passed admission. */
    DOCUMENT("documents"),

    /** Superseded and historical revisions, retained per the CDE archive rules. */
    REVISION("revisions"),

    /** Generated previews and thumbnails. Reproducible, so cheap to lose. */
    DERIVATIVE("derivatives"),

    /**
     * Uploads mid-scan. Nothing here is downloadable or referenced by the
     * application until it has been scanned clean and promoted.
     */
    QUARANTINE("quarantine"),

    /** Generated exports and reports awaiting collection. Short-lived. */
    EXPORT("exports");

    private final String segment;

    StorageCategory(String segment) {
        this.segment = segment;
    }

    /** The path segment this category contributes to a key. */
    public String segment() {
        return segment;
    }
}
