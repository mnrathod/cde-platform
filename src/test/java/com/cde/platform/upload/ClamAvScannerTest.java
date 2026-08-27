package com.cde.platform.upload;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ClamAV wire protocol, against a daemon that only exists for this test.
 *
 * <p>Running a real ClamAV means a several-hundred-megabyte image and a
 * signature download, which is not something a unit test should need — but
 * "the client compiles" is not evidence that it frames the protocol correctly,
 * and a scanner that silently mis-frames reports every file as clean. So the
 * test speaks the daemon's half: it reads the command, reassembles the
 * length-prefixed chunks, and answers. That verifies the framing, which is the
 * part that can be wrong.
 *
 * <p>What it does not verify is that a real clamd agrees with this reading of
 * its protocol. That needs an integration environment with the daemon in it.
 */
class ClamAvScannerTest {

    @TempDir
    Path files;

    private ServerSocket daemon;

    @AfterEach
    void closeDaemon() throws IOException {
        if (daemon != null && !daemon.isClosed()) {
            daemon.close();
        }
    }

    /**
     * Starts a socket that speaks clamd's half of INSTREAM.
     *
     * @param reply what to answer with, after the stream ends
     * @return the bytes the client sent, once it has finished sending them
     */
    private CompletableFuture<byte[]> startDaemonAnswering(String reply) throws IOException {
        daemon = new ServerSocket(0);
        var received = new CompletableFuture<byte[]>();

        Thread.ofVirtual().start(() -> {
            try (Socket connection = daemon.accept();
                 InputStream in = connection.getInputStream();
                 OutputStream out = connection.getOutputStream()) {

                // The command, terminated by a null byte.
                var command = new StringBuilder();
                int character;
                while ((character = in.read()) != -1 && character != 0) {
                    command.append((char) character);
                }
                if (!"zINSTREAM".contentEquals(command)) {
                    received.completeExceptionally(new IllegalStateException(
                        "The client sent '" + command + "' rather than zINSTREAM"));
                    return;
                }

                // Length-prefixed chunks until a zero-length one.
                var body = new ByteArrayOutputStream();
                while (true) {
                    byte[] lengthBytes = in.readNBytes(4);
                    if (lengthBytes.length < 4) {
                        break;
                    }
                    int length = ByteBuffer.wrap(lengthBytes).getInt();
                    if (length == 0) {
                        break;
                    }
                    body.write(in.readNBytes(length));
                }

                out.write(reply.getBytes(StandardCharsets.US_ASCII));
                out.write(0);
                out.flush();
                received.complete(body.toByteArray());

            } catch (Exception e) {
                received.completeExceptionally(e);
            }
        });
        return received;
    }

    private Path fileOf(String content) throws IOException {
        Path path = files.resolve("upload.bin");
        Files.writeString(path, content);
        return path;
    }

    @Test
    @DisplayName("the file reaches the daemon byte for byte")
    void streamsTheFileIntact() throws Exception {
        var received = startDaemonAnswering("stream: OK");
        String content = "the contents of an uploaded drawing";

        var scanner = new ClamAvScanner("127.0.0.1", daemon.getLocalPort(), 5000);
        scanner.scan(fileOf(content));

        // The property that matters and that compiling cannot establish: the
        // chunk framing reassembles to exactly what was sent. A client that
        // mis-frames delivers a truncated file, which every scanner reports as
        // clean.
        assertThat(new String(received.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8))
            .isEqualTo(content);
    }

    @Test
    @DisplayName("a clean reply is a clean verdict")
    void readsACleanReply() throws Exception {
        startDaemonAnswering("stream: OK");

        var verdict = new ClamAvScanner("127.0.0.1", daemon.getLocalPort(), 5000)
            .scan(fileOf("harmless"));

        assertThat(verdict.clean()).isTrue();
    }

    @Test
    @DisplayName("an infected reply names the signature")
    void readsAnInfectedReply() throws Exception {
        startDaemonAnswering("stream: Eicar-Test-Signature FOUND");

        var verdict = new ClamAvScanner("127.0.0.1", daemon.getLocalPort(), 5000)
            .scan(fileOf("whatever the test file contains"));

        assertThat(verdict.clean()).isFalse();
        assertThat(verdict.signature()).isEqualTo("Eicar-Test-Signature");
    }

    @Test
    @DisplayName("an unrecognised reply is not treated as clean")
    void refusesToGuessAtAnUnknownReply() throws Exception {
        startDaemonAnswering("ERROR: something went wrong");

        // The failure mode this exists to prevent: a daemon that says something
        // unexpected, read optimistically as "not infected". Whether an
        // unreadable answer blocks the upload is the caller's policy, and it
        // cannot make that decision if this quietly returns clean.
        assertThatThrownBy(() -> new ClamAvScanner("127.0.0.1", daemon.getLocalPort(), 5000)
                .scan(fileOf("harmless")))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("unrecognised reply");
    }

    @Test
    @DisplayName("an unreachable daemon raises rather than passing the file")
    void raisesWhenTheDaemonIsAbsent() throws Exception {
        // A port nothing is listening on. Reserved and released, so the
        // connection is refused rather than timing out.
        int freePort;
        try (ServerSocket probe = new ServerSocket(0)) {
            freePort = probe.getLocalPort();
        }

        assertThatThrownBy(() -> new ClamAvScanner("127.0.0.1", freePort, 1000)
                .scan(fileOf("harmless")))
            .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("an empty file is still framed correctly")
    void handlesAnEmptyFile() throws Exception {
        var received = startDaemonAnswering("stream: OK");
        Path empty = files.resolve("empty.bin");
        Files.write(empty, new byte[0]);

        var verdict = new ClamAvScanner("127.0.0.1", daemon.getLocalPort(), 5000).scan(empty);

        assertThat(verdict.clean()).isTrue();
        assertThat(received.get(5, TimeUnit.SECONDS)).isEmpty();
    }
}
