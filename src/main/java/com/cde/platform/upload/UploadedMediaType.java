package com.cde.platform.upload;

import java.util.Map;

/**
 * The content type recorded for an uploaded file.
 *
 * <p>One definition, shared by both upload paths. They disagreed: the direct
 * endpoint mapped the extension explicitly and the chunked one asked Tika, so
 * the same file arriving by different routes could be stored with different
 * types — and the viewer decides what to render by matching on that string.
 *
 * <p>Mapped from the extension rather than sniffed, because the callers that
 * read this are choosing a renderer, not making a security decision. The types
 * that matter here are the drawing and model formats, which content sniffing
 * reports as generic binary or plain text. Where the type <em>is</em> a
 * security decision — deciding whether to accept the bytes at all — that check
 * belongs on the content, not here.
 */
public final class UploadedMediaType {

    private UploadedMediaType() {
    }

    private static final String FALLBACK = "application/octet-stream";

    private static final Map<String, String> BY_EXTENSION = Map.ofEntries(
        Map.entry("png",  "image/png"),
        Map.entry("jpg",  "image/jpeg"),
        Map.entry("jpeg", "image/jpeg"),
        Map.entry("gif",  "image/gif"),
        Map.entry("webp", "image/webp"),
        Map.entry("svg",  "image/svg+xml"),
        Map.entry("pdf",  "application/pdf"),
        Map.entry("dxf",  "application/dxf"),
        Map.entry("dwg",  "application/dwg"),
        Map.entry("doc",  "application/msword"),
        Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        Map.entry("xls",  "application/vnd.ms-excel"),
        Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        Map.entry("ppt",  "application/vnd.ms-powerpoint"),
        Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
        Map.entry("odt",  "application/vnd.oasis.opendocument.text"),
        Map.entry("ods",  "application/vnd.oasis.opendocument.spreadsheet"),
        Map.entry("odp",  "application/vnd.oasis.opendocument.presentation"),
        Map.entry("rtf",  "application/rtf"),
        Map.entry("txt",  "text/plain"),
        Map.entry("csv",  "text/csv"),
        Map.entry("ifc",  "application/x-step"),
        Map.entry("rvt",  FALLBACK),
        Map.entry("rfa",  FALLBACK),
        Map.entry("glb",  "model/gltf-binary"),
        Map.entry("gltf", "model/gltf+json"),
        Map.entry("obj",  "text/plain"),
        Map.entry("stl",  FALLBACK),
        Map.entry("ply",  FALLBACK),
        Map.entry("dae",  "model/vnd.collada+xml"));

    /**
     * @param declaredContentType what the client said, used only when it says
     *                            something more specific than "some bytes".
     */
    public static String of(String clientFileName, String declaredContentType) {
        String fromExtension = BY_EXTENSION.get(StoredFileName.extensionOf(clientFileName));
        if (fromExtension != null && !fromExtension.equals(FALLBACK)) {
            return fromExtension;
        }
        if (declaredContentType != null
            && !declaredContentType.isBlank()
            && !declaredContentType.equals(FALLBACK)) {
            return declaredContentType;
        }
        return fromExtension != null ? fromExtension : FALLBACK;
    }
}
