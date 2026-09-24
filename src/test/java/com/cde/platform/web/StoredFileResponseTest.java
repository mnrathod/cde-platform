package com.cde.platform.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The response is backed by the file, not by a copy of it.
 *
 * <p>This exists because of a gap in the route tests. Those assert that a
 * download answers a {@code Range} request, which a {@code byte[]} body
 * cannot do — and that is the regression that actually happened, so it is
 * worth catching there. But a {@code ByteArrayResource} answers ranges
 * perfectly well while still holding the whole file in memory, so a change
 * from streaming to that would leave every route test green. Confirmed by
 * making it: the download suite passed.
 *
 * <p>So the memory property is asserted here instead, on the one place all
 * those routes go through, and asserted directly: the body is file-backed.
 * {@link Resource#isFile()} is the question "can this be read from disk
 * without being materialised first", which is exactly §7.7's requirement and
 * not an accident of which class was chosen.
 */
@DisplayName("serving a stored file")
class StoredFileResponseTest {

    @TempDir Path storage;

    private Path fileContaining(String body) throws IOException {
        Path path = storage.resolve("stored.bin");
        Files.writeString(path, body, StandardCharsets.UTF_8);
        return path;
    }

    @Test
    @DisplayName("the body is backed by the file on disk, not by a copy in memory")
    void bodyIsFileBacked() throws IOException {
        Path path = fileContaining("some bytes");

        ResponseEntity<Resource> response =
            StoredFileResponse.streaming(path, MediaType.APPLICATION_PDF);

        Resource body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.isFile())
            .as("a response that is not file-backed has read the whole document into "
                + "memory, which §7.7 forbids")
            .isTrue();
        assertThat(body.getFile().toPath()).isEqualTo(path);
    }

    @Test
    @DisplayName("a buffered resource would not satisfy that, which is the point")
    void aBufferedResourceIsNotFileBacked() {
        // The control. Without it the assertion above could be trivially
        // true of anything, and the distinction it is drawing would be
        // invisible to whoever reads this next.
        assertThat(new ByteArrayResource("some bytes".getBytes(StandardCharsets.UTF_8)).isFile())
            .isFalse();
    }

    @Test
    @DisplayName("the length is the file's own, so a download is not truncated")
    void lengthIsTheFileLength() throws IOException {
        Path path = fileContaining("exactly twenty two");

        ResponseEntity<Resource> response =
            StoredFileResponse.streaming(path, MediaType.APPLICATION_PDF);

        assertThat(response.getHeaders().getContentLength()).isEqualTo(Files.size(path));
    }

    @Test
    @DisplayName("the content type is the one the caller asked for")
    void contentTypeIsTheCallersChoice() throws IOException {
        Path path = fileContaining("glTF");

        ResponseEntity<Resource> response = StoredFileResponse.streaming(
            path, MediaType.parseMediaType("model/gltf-binary"));

        assertThat(response.getHeaders().getContentType())
            .isEqualTo(MediaType.parseMediaType("model/gltf-binary"));
    }

    @Test
    @DisplayName("the caller's own headers survive")
    void callerHeadersAreKept() throws IOException {
        // The route tests check this end to end, but it is worth holding
        // here too: this is where they could be dropped for every route at
        // once.
        Path path = fileContaining("some bytes");
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"sheet_v2.pdf\"");
        headers.set("X-Source-Type", "pdf");

        ResponseEntity<Resource> response =
            StoredFileResponse.streaming(path, MediaType.APPLICATION_PDF, headers);

        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
            .isEqualTo("attachment; filename=\"sheet_v2.pdf\"");
        assertThat(response.getHeaders().getFirst("X-Source-Type")).isEqualTo("pdf");
    }

    @Test
    @DisplayName("an empty file is served as an empty body, not as a failure")
    void emptyFileIsServed() throws IOException {
        Path path = fileContaining("");

        ResponseEntity<Resource> response =
            StoredFileResponse.streaming(path, MediaType.APPLICATION_PDF);

        assertThat(response.getHeaders().getContentLength()).isZero();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("a file that has gone between the check and the send fails loudly")
    void missingFileIsReported() {
        // Rather than a 200 with a zero length, which reads to the client
        // as a document that is genuinely empty.
        assertThatThrownBy(() ->
            StoredFileResponse.streaming(storage.resolve("never-written.bin"),
                                         MediaType.APPLICATION_PDF))
            .isInstanceOf(IOException.class);
    }
}
