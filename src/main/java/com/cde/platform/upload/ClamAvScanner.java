package com.cde.platform.upload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Talks to a ClamAV daemon over its INSTREAM protocol.
 *
 * <p>Out of process, over a socket, by design rather than by accident: ClamAV
 * is GPLv2, and using it as a separate service invoked over a socket is what
 * keeps it out of this application's licensing. Linking or embedding it would
 * create a combined work.
 *
 * <p>INSTREAM streams the file to the daemon in length-prefixed chunks and ends
 * with a zero-length chunk. That is the reason to use it rather than SCAN,
 * which takes a path: with SCAN the daemon must be able to read the same
 * filesystem, which it cannot when the two run in separate containers — the
 * arrangement this is deployed in.
 *
 * <p>The file is read in bounded chunks and never held whole, so scanning a
 * two-gigabyte model costs one buffer rather than two gigabytes of heap.
 */
public class ClamAvScanner implements MalwareScanner {

    private static final Logger log = LoggerFactory.getLogger(ClamAvScanner.class);

    /**
     * ClamAV's default {@code StreamMaxLength} is 25 MB and it refuses a larger
     * chunk, so this stays well under it. Larger chunks would not be faster:
     * the daemon scans as it receives.
     */
    private static final int CHUNK_BYTES = 32 * 1024;

    /** The daemon answers a clean file with exactly this. */
    private static final String CLEAN_RESPONSE = "stream: OK";

    /** An infected reply reads "stream: <signature> FOUND". */
    private static final String FOUND_SUFFIX = "FOUND";

    private final String host;
    private final int port;
    private final int timeoutMillis;

    public ClamAvScanner(String host, int port, int timeoutMillis) {
        this.host = host;
        this.port = port;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public boolean isOperational() {
        return true;
    }

    @Override
    public ScanVerdict scan(Path path) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);

            try (OutputStream out = socket.getOutputStream();
                 InputStream in = socket.getInputStream();
                 InputStream file = Files.newInputStream(path)) {

                // The null byte is part of the command terminator: zINSTREAM
                // rather than nINSTREAM, because the z form is terminated
                // unambiguously and does not depend on the daemon's newline
                // configuration.
                out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));

                byte[] buffer = new byte[CHUNK_BYTES];
                int read;
                while ((read = file.read(buffer)) != -1) {
                    if (read == 0) {
                        continue;
                    }
                    // Four-byte big-endian length, then the bytes. A
                    // zero-length prefix means end of stream, which is why a
                    // zero-length read must not be written as a chunk — it
                    // would end the stream early and the daemon would report
                    // the truncated prefix as clean.
                    out.write(ByteBuffer.allocate(4).putInt(read).array());
                    out.write(buffer, 0, read);
                }
                out.write(ByteBuffer.allocate(4).putInt(0).array());
                out.flush();

                return interpret(readReply(in));
            }
        }
    }

    private String readReply(InputStream in) throws IOException {
        var reply = new StringBuilder();
        int character;
        while ((character = in.read()) != -1) {
            if (character == 0) {
                break;
            }
            reply.append((char) character);
        }
        return reply.toString().trim();
    }

    private ScanVerdict interpret(String reply) throws IOException {
        if (CLEAN_RESPONSE.equals(reply)) {
            return ScanVerdict.safe();
        }
        if (reply.endsWith(FOUND_SUFFIX)) {
            // "stream: Eicar-Test-Signature FOUND" → the signature between.
            int start = reply.indexOf(':') + 1;
            int end = reply.lastIndexOf(FOUND_SUFFIX);
            String signature = start > 0 && end > start
                ? reply.substring(start, end).trim() : "unnamed";
            log.warn("The malware scanner refused an upload: {}", signature);
            return ScanVerdict.infected(signature);
        }
        // Anything else — an ERROR reply, a truncated response, a daemon that
        // said something new — is not a clean result and must not be treated as
        // one. Whether that blocks the upload is the caller's policy decision.
        throw new IOException("The malware scanner gave an unrecognised reply: " + reply);
    }
}
