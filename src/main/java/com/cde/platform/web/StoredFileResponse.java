package com.cde.platform.web;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The bytes of a stored file, sent without first being held.
 *
 * <p>§7.7 forbids {@code Files.readAllBytes} on user-supplied content and
 * forbids holding an upload or an export in a {@code byte[]}. Five download
 * paths were doing exactly that — the viewer's PDF and image routes, the
 * model route, and the version download — so every request for a document
 * allocated the whole document on the heap and held it until the client had
 * finished reading. A handful of concurrent requests for a large drawing set
 * is all that needs, and §6.7.4 is explicit that a model measured in
 * gigabytes must not touch application memory at all.
 *
 * <p>{@link FileSystemResource} rather than an {@code InputStreamResource}:
 * both stream, but only this one can be read more than once, which is what
 * lets Spring serve a {@code Range} request from it. That matters here
 * beyond tidiness — a reader scrubbing through a large PDF in the viewer
 * fetches ranges, and a resumable download of a model needs them (§7.7).
 * It also reports its own length, so {@code Content-Length} stays correct
 * without anyone computing it.
 */
public final class StoredFileResponse {

    private StoredFileResponse() {
    }

    /**
     * A 200 carrying the file at {@code path}, streamed.
     *
     * <p>The caller supplies the headers it needs — disposition, caching,
     * whatever the route requires — and the content type and length are
     * filled in here, since getting either wrong is how a download arrives
     * truncated or as the wrong kind of file.
     *
     * @throws IOException if the file's size cannot be read, which means it
     *                     has gone between the caller's existence check and
     *                     this call
     */
    public static ResponseEntity<Resource> streaming(
            Path path, MediaType contentType, HttpHeaders headers) throws IOException {
        headers.setContentType(contentType);
        headers.setContentLength(Files.size(path));
        return new ResponseEntity<>(new FileSystemResource(path), headers, HttpStatus.OK);
    }

    /** As {@link #streaming(Path, MediaType, HttpHeaders)}, with no extra headers. */
    public static ResponseEntity<Resource> streaming(Path path, MediaType contentType)
            throws IOException {
        return streaming(path, contentType, new HttpHeaders());
    }
}
