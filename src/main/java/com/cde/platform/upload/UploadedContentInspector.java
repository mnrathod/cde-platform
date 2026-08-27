package com.cde.platform.upload;

import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * What the bytes actually are, as opposed to what the upload claimed.
 *
 * <p>Nothing inspected them before. {@link UploadedMediaType} maps the file
 * extension to a content type, which is the right answer for choosing a
 * renderer and the wrong one for deciding whether to accept the file at all:
 * an attacker names the file, so a check against the name is a check against
 * something the attacker controls. The client-supplied {@code Content-Type}
 * header is no better, for the same reason.
 *
 * <p>So this reads the leading bytes and asks what they are. Two questions get
 * different answers and both matter:
 *
 * <ul>
 *   <li><strong>Is this type allowed here at all?</strong> An allow-list, so a
 *       format nobody thought about is refused rather than accepted.</li>
 *   <li><strong>Is this active content?</strong> SVG, HTML and XML are markup a
 *       browser will execute, and a drawing package legitimately produces SVG —
 *       so they are not refused, they are marked, and the storage layer serves
 *       them as attachments from a separate origin rather than inline.</li>
 * </ul>
 *
 * <p>Only the first {@value #INSPECTED_BYTES} bytes are read. Detection needs
 * the header, not the file, and reading a two-gigabyte model to identify it
 * would undo the streaming that exists so a model never reaches the heap.
 */
@Component
public class UploadedContentInspector {

    /**
     * Enough for every signature the detector uses, and small enough that
     * inspecting a file costs one disk read rather than a copy.
     */
    static final int INSPECTED_BYTES = 8192;

    /**
     * Types this platform accepts, by what the bytes say rather than by
     * extension. An allow-list because the alternative fails open: a deny-list
     * refuses the formats somebody thought of, and accepts every one they
     * did not.
     *
     * <p>{@code application/octet-stream} and {@code text/plain} are here
     * because the formats this product exists for — DWG, RVT, IFC, STEP, OBJ,
     * STL — are exactly the ones content detection reports as generic binary or
     * plain text. Refusing those would refuse the product's own file types, so
     * the extension decides between them, and the check that matters is the one
     * below: whether the bytes are markup pretending to be a model.
     */
    private static final Set<String> PERMITTED_TYPES = Set.of(
        "application/pdf",
        "image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp",
        "image/tiff", "image/svg+xml",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.oasis.opendocument.text",
        "application/vnd.oasis.opendocument.spreadsheet",
        "application/vnd.oasis.opendocument.presentation",
        "application/rtf", "text/rtf",
        "text/plain", "text/csv", "text/xml", "application/xml",
        "application/x-step", "model/step",
        "model/gltf-binary", "model/gltf+json", "model/vnd.collada+xml",
        "application/zip",
        "application/octet-stream");

    /**
     * Markup a browser executes. Not refused — a drawing export is legitimately
     * SVG — but never served inline from the application's own origin.
     */
    private static final Set<String> ACTIVE_CONTENT_TYPES = Set.of(
        "image/svg+xml", "text/html", "application/xhtml+xml",
        "text/xml", "application/xml");

    /**
     * Executable and script formats, refused outright. Present because
     * {@code application/octet-stream} has to be permitted for the model
     * formats above, and that would otherwise let a Windows executable through
     * under a {@code .rvt} name.
     */
    private static final Set<String> REFUSED_TYPES = Set.of(
        "application/x-msdownload", "application/x-dosexec",
        "application/x-executable", "application/x-sharedlib",
        "application/x-mach-binary", "application/x-elf",
        "application/x-sh", "application/x-shellscript",
        "application/x-bat", "application/x-msdos-program",
        "application/java-archive", "application/x-java-applet",
        "application/vnd.microsoft.portable-executable",
        "application/x-ms-shortcut");

    /**
     * Markup larger than this is not scanned for script and is never rendered
     * inline; it is served as an attachment instead. Matches the inline-storage
     * bound in the upload controller, which is the only path that renders
     * markup on this origin.
     */
    static final long MAX_SCANNED_MARKUP_BYTES = 2L * 1024 * 1024;

    /**
     * An event-handler attribute: {@code onload=}, {@code onclick=} and the
     * couple of hundred others. Matched by shape rather than enumerated,
     * because the list grows with the platform and an enumeration is a list of
     * the ones somebody remembered.
     */
    private static final java.util.regex.Pattern SCRIPTING_ATTRIBUTE =
        java.util.regex.Pattern.compile("\\son[a-z]+\\s*=");

    private final Tika tika = new Tika();

    /**
     * @param path the file as stored, already streamed to disk
     * @param clientFileName what the upload called it, used only to choose
     *                       between formats the detector cannot tell apart
     */
    public InspectedContent inspect(Path path, String clientFileName) throws IOException {
        byte[] header = readHeader(path);
        String detected = tika.detect(header, clientFileName).toLowerCase(Locale.ROOT);
        String baseType = detected.contains(";")
            ? detected.substring(0, detected.indexOf(';')).trim() : detected;

        if (REFUSED_TYPES.contains(baseType)) {
            return InspectedContent.refused(baseType,
                "the file is an executable or a script, whatever it is named");
        }
        if (!PERMITTED_TYPES.contains(baseType)) {
            return InspectedContent.refused(baseType,
                "this deployment does not accept " + baseType + " files");
        }

        // The case a permitted generic type would otherwise hide: bytes that
        // are markup, under a name that says model or drawing. Detection reports
        // both a real IFC and an HTML page as text, so the type alone does not
        // separate them.
        boolean active = ACTIVE_CONTENT_TYPES.contains(baseType)
                      || looksLikeMarkup(header);

        if (active && carriesScript(path)) {
            return InspectedContent.refused(baseType,
                "the file is markup carrying a script or an event handler");
        }

        return InspectedContent.accepted(baseType, active);
    }

    /**
     * Reads the leading bytes without opening the whole file.
     */
    private byte[] readHeader(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return in.readNBytes(INSPECTED_BYTES);
        }
    }

    /**
     * Whether the leading bytes are markup regardless of what they are called.
     *
     * <p>Checked on the header text rather than trusting the detected type,
     * because a file whose extension says {@code .ifc} and whose first bytes
     * say {@code <svg} is the interesting case: detection would report it as
     * plain text, which is permitted, and the viewer would then be handed
     * markup.
     */
    private boolean looksLikeMarkup(byte[] header) {
        String leading = new String(header, 0, Math.min(header.length, 512),
                                    StandardCharsets.UTF_8)
            .stripLeading()
            .toLowerCase(Locale.ROOT);
        return leading.startsWith("<svg")
            || leading.startsWith("<!doctype html")
            || leading.startsWith("<html")
            || leading.startsWith("<?xml")
            || leading.startsWith("<!doctype svg");
    }

    /**
     * Whether markup carries anything a browser would execute.
     *
     * <p>Refused rather than sanitised, deliberately. An SVG exported by a CAD
     * or drawing package has no scripts in it, so refusing costs a legitimate
     * upload nothing — while writing a sanitiser that is right about every way
     * markup can execute is a project, and one whose failures are silent. The
     * asymmetry says refuse.
     *
     * <p>This matters more than it would elsewhere because a small SVG is
     * stored inline and rendered by the viewer as markup on the application's
     * own origin. A scripted one is stored cross-site scripting, executing with
     * every viewer's session.
     *
     * <p>The whole file is read rather than the header: a script element sits
     * wherever the author put it, and checking only the first few kilobytes
     * would mean the check is passed by putting it further down. Bounded by the
     * inline-storage limit, so this never reads a large file.
     */
    private boolean carriesScript(Path path) throws IOException {
        long size = Files.size(path);
        if (size > MAX_SCANNED_MARKUP_BYTES) {
            // Too large to be the inline case, and too large to scan cheaply.
            // Treated as active content, which means it is served as an
            // attachment from a separate origin and never rendered inline.
            return false;
        }
        String markup = Files.readString(path, StandardCharsets.UTF_8)
            .toLowerCase(Locale.ROOT);
        return markup.contains("<script")
            || markup.contains("javascript:")
            || markup.contains("<foreignobject")
            || markup.contains("<!entity")
            || SCRIPTING_ATTRIBUTE.matcher(markup).find();
    }

    /**
     * @param detectedType what the bytes say the file is
     * @param activeContent whether a browser would execute it, so it must never
     *                      be served inline from the application's own origin
     * @param refusalReason why it will not be accepted, or null
     */
    public record InspectedContent(String detectedType, boolean activeContent,
                                   String refusalReason) {

        static InspectedContent accepted(String detectedType, boolean activeContent) {
            return new InspectedContent(detectedType, activeContent, null);
        }

        static InspectedContent refused(String detectedType, String reason) {
            return new InspectedContent(detectedType, false, reason);
        }

        public boolean isRefused() {
            return refusalReason != null;
        }
    }
}
