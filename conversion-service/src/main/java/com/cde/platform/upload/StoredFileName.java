package com.cde.platform.upload;

import java.util.Locale;
import java.util.UUID;

/**
 * What an uploaded file is called on disk, and what it is called to a person.
 *
 * <p>These are deliberately two different things. The name the client sent is
 * metadata: it is shown in listings and offered back on download, and it is
 * whatever the client chose to send — separators, dots, control characters and
 * all. The name on disk is generated here and contains nothing the client
 * supplied except a validated extension.
 *
 * <p>The previous storage name was a generated prefix joined to the client's
 * name. That was not exploitable — the prefix made the first path segment a
 * literal directory that does not exist, so the filesystem refused to follow
 * anything after it — but it meant an ordinary filename containing a slash
 * produced a `500`, and it left the storage layout depending on that accident
 * for its safety.
 */
public final class StoredFileName {

    private StoredFileName() {
    }

    /** Longest display name kept. Comfortably past any real filename. */
    private static final int MAX_DISPLAY_LENGTH = 255;

    /** Extensions are letters and digits only, and short. */
    private static final int MAX_EXTENSION_LENGTH = 8;

    /**
     * The name to store the bytes under: a fresh identifier, plus the client's
     * extension when it is one we can recognise.
     *
     * <p>The extension is carried over because tooling and the viewer read it,
     * and it is validated rather than trusted — anything with a separator, a
     * dot, or a character outside letters and digits is dropped entirely.
     */
    public static String forStorage(String clientFileName) {
        String extension = extensionOf(clientFileName);
        return extension.isEmpty()
            ? UUID.randomUUID().toString()
            : UUID.randomUUID() + "." + extension;
    }

    /**
     * The name to show and to return: the client's, reduced to its last path
     * segment and stripped of anything that is not safely printable.
     */
    public static String forDisplay(String clientFileName) {
        if (clientFileName == null || clientFileName.isBlank()) {
            return "unnamed";
        }

        // Both separators, because a name is as likely to arrive from a Windows
        // client as a POSIX one and only the last segment is the filename.
        String lastSegment = clientFileName
            .substring(Math.max(clientFileName.lastIndexOf('/'),
                                clientFileName.lastIndexOf('\\')) + 1);

        String cleaned = lastSegment.replaceAll("[\\p{Cntrl}]", "").trim();
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) {
            return "unnamed";
        }
        return cleaned.length() > MAX_DISPLAY_LENGTH
            ? cleaned.substring(0, MAX_DISPLAY_LENGTH)
            : cleaned;
    }

    /** The lower-case extension, or empty when there is not a plausible one. */
    public static String extensionOf(String clientFileName) {
        if (clientFileName == null) {
            return "";
        }
        int dot = clientFileName.lastIndexOf('.');
        if (dot < 0 || dot == clientFileName.length() - 1) {
            return "";
        }

        String candidate = clientFileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (candidate.length() > MAX_EXTENSION_LENGTH
            || !candidate.chars().allMatch(Character::isLetterOrDigit)) {
            return "";
        }
        return candidate;
    }
}
