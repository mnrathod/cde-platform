package com.cde.platform.fetch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Streams the content behind an integrator-supplied URL onto local disk.
 *
 * <p>ADR 12 has the host CDE mint a short-lived link — a Graph download URL, an
 * S3 presigned GET, an Azure SAS, a GCS signed URL — and hand it to us. That is
 * what collapses four storage platforms into one code path without us holding
 * anyone's credentials. This class does the transport half of that; whether the
 * destination is permissible at all is {@link DestinationCheck}'s question, and
 * it is asked before a socket is opened.
 *
 * <p>The file lands in quarantine and nothing has looked at it yet. What it
 * actually is — magic bytes, malware, active content — is decided afterwards by
 * the existing upload admission pipeline, which already knows how, and which a
 * second implementation here would only get differently wrong.
 *
 * <h2>What is bounded, and why each bound is separate</h2>
 * <ul>
 *   <li><strong>Size</strong>, counted as it is written rather than believed
 *       from {@code Content-Length}. A declared length is a claim by the
 *       server we were pointed at; the running total is a fact. The declared
 *       length is still checked first, because refusing before the transfer
 *       is cheaper than refusing during it.</li>
 *   <li><strong>Connect and response timeouts</strong>, for a destination that
 *       never answers.</li>
 *   <li><strong>A transfer deadline</strong>, for one that answers promptly
 *       and then dribbles. A response timeout is satisfied the moment headers
 *       arrive, so without this a slow-loris response holds a thread and a
 *       disk allocation for as long as it likes.</li>
 *   <li><strong>Redirects are not followed.</strong> A redirect is a second
 *       destination that no policy check has seen, which is the standard way
 *       an allow-listed URL turns into {@code 169.254.169.254}. Re-validating
 *       each hop would also be defensible; refusing is simpler, and a storage
 *       platform's own signed URL does not redirect.</li>
 * </ul>
 *
 * <h2>The residual risk, stated rather than papered over</h2>
 * The policy resolves the host and this client resolves it again, so a name
 * whose DNS answer changes between the two calls is checked at one address and
 * connected to at another. That window cannot be closed from here: the JDK's
 * client offers no supported way to pin a connection to an
 * already-validated address, and connecting to a raw IP with a spoofed
 * {@code Host} header breaks certificate validation, which trades a narrow
 * hole for a wider one. It is closed instead by the two controls §5.12 A10
 * names alongside address validation: a host allow-list
 * ({@code cde.fetch.permitted-hosts}) and a filtered egress proxy. A
 * deployment that sets neither is relying on the pre-check alone, and should
 * know that.
 */
public class RemoteContentFetcher {

    private static final Logger log = LoggerFactory.getLogger(RemoteContentFetcher.class);

    /**
     * 64 KiB. Large enough that a 2 GB transfer is not made of syscalls, small
     * enough that concurrent fetches do not add up to a heap problem — the
     * buffer is the only part of the file that is ever in memory (§7.7).
     */
    private static final int BUFFER_BYTES = 64 * 1024;

    /**
     * The {@code filename} parameter of a {@code Content-Disposition} header.
     *
     * <p>Used for display only, and sanitised by the caller before it is shown
     * or stored as metadata. It never influences where the file is written:
     * storage keys are server-generated, so a filename of {@code ../../etc}
     * has nowhere to go (§5.13.6, §11).
     */
    private static final Pattern DISPOSITION_FILENAME =
        Pattern.compile("filename\\s*=\\s*\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE);

    private final HttpClient httpClient;
    private final DestinationCheck destinationCheck;
    private final long maxContentSizeBytes;
    private final Duration responseTimeout;
    private final Duration transferTimeout;

    public RemoteContentFetcher(HttpClient httpClient,
                                DestinationCheck destinationCheck,
                                FetchProperties properties) {
        this.httpClient = httpClient;
        this.destinationCheck = destinationCheck;
        this.maxContentSizeBytes = properties.getMaxContentSize().toBytes();
        this.responseTimeout = properties.getResponseTimeout();
        this.transferTimeout = properties.getTransferTimeout();
    }

    /**
     * Fetches {@code source} into {@code destination}, which must not exist.
     *
     * <p>A failure leaves nothing behind: a partial file is deleted before the
     * exception propagates, so a caller cannot mistake a truncated download
     * for a document.
     *
     * @throws FetchNotPermittedException if the destination is refused by
     *         policy, before anything is connected to
     * @throws ContentFetchFailedException if the fetch does not complete —
     *         refused, unreachable, too large, or too slow
     */
    public FetchedContent fetchTo(URI source, Path destination) {
        destinationCheck.check(source);

        HttpRequest request = HttpRequest.newBuilder(source)
            .GET()
            .timeout(responseTimeout)
            .build();

        try {
            HttpResponse<InputStream> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            return receive(response, destination);
        } catch (IOException e) {
            deleteQuietly(destination);
            throw new ContentFetchFailedException(
                "The link could not be read. Check that it has not expired and "
                + "that the storage service is reachable.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deleteQuietly(destination);
            throw new ContentFetchFailedException("The fetch was interrupted.", e);
        }
    }

    private FetchedContent receive(HttpResponse<InputStream> response, Path destination)
            throws IOException {
        if (response.statusCode() != 200) {
            drainQuietly(response);
            throw new ContentFetchFailedException(
                "The storage service answered " + response.statusCode()
                + " for that link. A signed link that has expired usually answers 403.");
        }
        refuseIfDeclaredTooLarge(response);

        try {
            long sizeBytes = copyBounded(response.body(), destination);
            return new FetchedContent(
                sizeBytes,
                header(response, "content-type").orElse(""),
                declaredFileName(response).orElse(""));
        } catch (RuntimeException | IOException e) {
            deleteQuietly(destination);
            throw e;
        }
    }

    /**
     * Copies the body, stopping the moment it exceeds the cap.
     *
     * <p>Reading one byte past the limit and then refusing is deliberate: it
     * distinguishes a file exactly at the cap, which is fine, from one over it,
     * without needing the sender to have told the truth about its length.
     */
    private long copyBounded(InputStream body, Path destination) throws IOException {
        long deadlineNanos = System.nanoTime() + transferTimeout.toNanos();
        byte[] buffer = new byte[BUFFER_BYTES];
        long total = 0;

        Files.createDirectories(destination.getParent());
        try (InputStream in = body;
             OutputStream out = Files.newOutputStream(
                 destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxContentSizeBytes) {
                    throw new ContentFetchFailedException(
                        "That file is larger than this deployment accepts ("
                        + maxContentSizeBytes + " bytes).");
                }
                if (System.nanoTime() > deadlineNanos) {
                    throw new ContentFetchFailedException(
                        "The transfer did not finish within " + transferTimeout + ".");
                }
                out.write(buffer, 0, read);
            }
        }
        return total;
    }

    /**
     * Refuses on the declared length before transferring anything.
     *
     * <p>Defence in depth rather than the control: the running total in
     * {@link #copyBounded} is what actually holds, because a declared length
     * can be absent or a lie. This exists so the common honest case is refused
     * without opening a file, and so the caller is told the size — "3.4 GB
     * against a 2 GB limit" is something an integrator can act on, where
     * "too large" only tells them to guess.
     *
     * <p>The message is deliberately distinguishable from the running-total
     * one. Two branches that report identically are two branches a test cannot
     * tell apart, and one of them then goes unverified.
     */
    private void refuseIfDeclaredTooLarge(HttpResponse<InputStream> response) {
        Optional<Long> declared = header(response, "content-length").flatMap(this::parseLong);
        if (declared.isPresent() && declared.get() > maxContentSizeBytes) {
            drainQuietly(response);
            throw new ContentFetchFailedException(
                "That file is declared as " + declared.get() + " bytes, and this "
                + "deployment accepts at most " + maxContentSizeBytes + " bytes.");
        }
    }

    private Optional<String> declaredFileName(HttpResponse<InputStream> response) {
        return header(response, "content-disposition").flatMap(disposition -> {
            Matcher matcher = DISPOSITION_FILENAME.matcher(disposition);
            return matcher.find() ? Optional.of(matcher.group(1).trim()) : Optional.empty();
        });
    }

    private Optional<String> header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name);
    }

    private Optional<Long> parseLong(String value) {
        try {
            return Optional.of(Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private void drainQuietly(HttpResponse<InputStream> response) {
        try (InputStream body = response.body()) {
            body.readNBytes(BUFFER_BYTES);
        } catch (IOException e) {
            log.debug("Could not drain the body of a refused fetch", e);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // Worth knowing about — a quarantine directory filling with
            // partial downloads is a disk-exhaustion problem — but not worth
            // replacing the real failure with this one.
            log.warn("A partial fetch could not be deleted from quarantine", e);
        }
    }

    /**
     * What arrived, and what the far end claimed about it.
     *
     * @param sizeBytes           what was actually written, counted here
     * @param declaredContentType the {@code Content-Type} header. A claim, not
     *                            a finding: the type is decided from magic
     *                            bytes by the admission pipeline (§5.13.3),
     *                            and this is kept only for diagnostics.
     * @param declaredFileName    the {@code Content-Disposition} filename, or
     *                            empty. Untrusted, display-only, and sanitised
     *                            by the caller (§5.13.6).
     */
    public record FetchedContent(long sizeBytes,
                                 String declaredContentType,
                                 String declaredFileName) {}
}
