package com.cde.platform.security;

import java.util.Set;

/**
 * The permissions that gate what may be done to a document and its file.
 *
 * <p>These names are not new. Every endpoint on the document surface already
 * documents one of them as its requirement — "Requires the {@code
 * document:read} permission" and so on, in the description a customer reads in
 * the published specification. What did not exist was the constant, the grant,
 * or the check: the whole surface was gated by {@code
 * .anyRequest().authenticated()}, so the documented requirement was true of
 * nothing. A viewer, whose entire permission set is read access, could delete a
 * document, redact it, rearrange its pages or restore an old version. The
 * vocabulary is written here to match what was already published rather than to
 * invent a second one beside it.
 *
 * <p>Three verbs rather than two, because reading, replacing and reprocessing
 * are held by different people. An engineer who uploads drawings is not
 * thereby entitled to burn a redaction into one, and the operations that
 * rewrite a file in place are the ones that cannot be undone.
 *
 * <p>Deliberately separate from {@link com.cde.platform.cde.domain.ContainerPermission}.
 * A document is a file with revisions; an information container is the
 * contractual record in the ISO 19650 sense, and its state machine decides who
 * may move it. Folding the two together would mean anyone permitted to upload
 * a drawing could also publish it.
 */
public final class DocumentPermission {

    private DocumentPermission() {
    }

    /**
     * See a document's metadata, list them, download the file, view it, and
     * compare two of them.
     */
    public static final String READ = "document:read";

    /**
     * Add a document, replace its file, change its status, delete it, and
     * restore an earlier version.
     *
     * <p>Delete and restore sit here rather than under a verb of their own
     * because both are ways of deciding which bytes the document currently is,
     * which is the same authority as putting them there in the first place.
     */
    public static final String WRITE = "document:write";

    /**
     * Rewrite the file's content: flatten annotations, redact, OCR, rearrange
     * or extract pages, and add or fill form fields.
     *
     * <p>Distinct from {@link #WRITE} because the consequences differ in kind.
     * Writing replaces a document with a new revision and leaves the old one
     * retrievable. Processing destroys information inside the file on purpose —
     * a redaction that could be undone would not be a redaction — so the
     * authority to do it is granted separately from the authority to upload.
     */
    public static final String PROCESS = "document:process";

    public static final Set<String> ALL = Set.of(READ, WRITE, PROCESS);
}
