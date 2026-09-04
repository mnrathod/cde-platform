package com.cde.platform.fetch;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the fetcher refuses to bring back.
 *
 * <p>Runs against a real loopback HTTP server rather than a mocked client,
 * because every bound under test is about what a *server* does — lying about
 * its length, redirecting, stalling — and a mock can only reproduce the
 * behaviour someone remembered to write.
 *
 * <p>The destination check is a no-op here. Reaching loopback is exactly what
 * {@link FetchDestinationPolicy} exists to refuse, and it goes on refusing it:
 * that judgement is tested in {@link FetchDestinationPolicyTest}, and this
 * file tests the transport it guards.
 */
class RemoteContentFetcherTest {

    private static final DestinationCheck PERMIT_EVERYTHING = target -> { };

    private HttpServer server;
    private URI baseUri;

    @TempDir
    Path quarantine;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void serve(String path, HttpHandler handler) {
        server.createContext(path, handler);
    }

    private static void respond(HttpExchange exchange, int status, long length, byte[] body)
            throws IOException {
        exchange.sendResponseHeaders(status, length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private RemoteContentFetcher fetcher(DataSize maxSize, Duration transferTimeout) {
        FetchProperties properties = new FetchProperties();
        properties.setMaxContentSize(maxSize);
        properties.setTransferTimeout(transferTimeout);
        properties.setResponseTimeout(Duration.ofSeconds(10));
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        return new RemoteContentFetcher(client, PERMIT_EVERYTHING, properties);
    }

    private RemoteContentFetcher fetcher() {
        return fetcher(DataSize.ofMegabytes(1), Duration.ofSeconds(30));
    }

    private Path destination() {
        return quarantine.resolve("fetched.bin");
    }

    @Test
    @DisplayName("streams the body to disk and reports the size it actually wrote")
    void streamsToDisk() throws IOException {
        byte[] body = "a synthetic drawing".getBytes(StandardCharsets.UTF_8);
        serve("/doc", exchange -> respond(exchange, 200, body.length, body));

        var fetched = fetcher().fetchTo(baseUri.resolve("/doc"), destination());

        assertThat(fetched.sizeBytes()).isEqualTo(body.length);
        assertThat(Files.readAllBytes(destination())).isEqualTo(body);
    }

    @Test
    @DisplayName("stops at the cap even when Content-Length understated the body")
    void refusesOversizeBodyDespiteHonestLookingHeader() {
        // The size limit has to be a running total, not a header check: a
        // server that declares 10 bytes and sends 10 megabytes passes every
        // check made before the transfer.
        byte[] body = new byte[200_000];
        serve("/liar", exchange -> respond(exchange, 200, 0, body));

        assertThatThrownBy(() ->
            fetcher(DataSize.ofBytes(1024), Duration.ofSeconds(30))
                .fetchTo(baseUri.resolve("/liar"), destination()))
            .isInstanceOf(ContentFetchFailedException.class)
            .hasMessageContaining("larger than this deployment accepts");
    }

    @Test
    @DisplayName("refuses on the declared length before transferring anything")
    void refusesDeclaredOversize() {
        // Asserts the *declared*-size wording, not merely that it was refused.
        // An earlier version of this test used the shared "too large" message,
        // so the running total refused the body anyway and the test passed with
        // this branch deleted — it proved nothing about the branch it named.
        byte[] body = new byte[100_000];
        serve("/big", exchange -> respond(exchange, 200, body.length, body));

        assertThatThrownBy(() ->
            fetcher(DataSize.ofBytes(1024), Duration.ofSeconds(30))
                .fetchTo(baseUri.resolve("/big"), destination()))
            .isInstanceOf(ContentFetchFailedException.class)
            .hasMessageContaining("declared as 100000 bytes")
            .hasMessageContaining("at most 1024 bytes");
    }

    @Test
    @DisplayName("leaves no partial file behind when a fetch fails")
    void deletesPartialFileOnFailure() {
        // A truncated download that stays on disk is worse than no download:
        // the next step cannot tell it from a document.
        byte[] body = new byte[200_000];
        serve("/liar", exchange -> respond(exchange, 200, 0, body));

        assertThatThrownBy(() ->
            fetcher(DataSize.ofBytes(1024), Duration.ofSeconds(30))
                .fetchTo(baseUri.resolve("/liar"), destination()))
            .isInstanceOf(ContentFetchFailedException.class);

        assertThat(destination()).doesNotExist();
    }

    @Test
    @DisplayName("does not follow a redirect, which would reach an unchecked destination")
    void refusesToFollowRedirects() {
        // The redirect target has passed no policy check. Following one is how
        // an allow-listed URL turns into the cloud metadata endpoint.
        AtomicInteger secondHopRequests = new AtomicInteger();
        serve("/moved", exchange -> {
            exchange.getResponseHeaders().add("Location", baseUri + "/elsewhere");
            respond(exchange, 302, -1, new byte[0]);
        });
        serve("/elsewhere", exchange -> {
            secondHopRequests.incrementAndGet();
            respond(exchange, 200, 2, "hi".getBytes(StandardCharsets.UTF_8));
        });

        assertThatThrownBy(() -> fetcher().fetchTo(baseUri.resolve("/moved"), destination()))
            .isInstanceOf(ContentFetchFailedException.class)
            .hasMessageContaining("302");

        assertThat(secondHopRequests).hasValue(0);
        assertThat(destination()).doesNotExist();
    }

    @Test
    @DisplayName("refuses a non-200 answer and says what the far end said")
    void refusesNonOkStatus() {
        serve("/expired", exchange -> respond(exchange, 403, -1, new byte[0]));

        assertThatThrownBy(() -> fetcher().fetchTo(baseUri.resolve("/expired"), destination()))
            .isInstanceOf(ContentFetchFailedException.class)
            .hasMessageContaining("403")
            .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("gives up on a response that starts promptly and then dribbles")
    void refusesStalledTransfer() {
        // Passes the response timeout — headers arrive at once — and would
        // otherwise hold a thread and a disk allocation indefinitely.
        serve("/slow", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(new byte[1024]);
                out.flush();
                Thread.sleep(600);
                out.write(new byte[1024]);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertThatThrownBy(() ->
            fetcher(DataSize.ofMegabytes(1), Duration.ofMillis(200))
                .fetchTo(baseUri.resolve("/slow"), destination()))
            .isInstanceOf(ContentFetchFailedException.class)
            .hasMessageContaining("did not finish");

        assertThat(destination()).doesNotExist();
    }

    @Test
    @DisplayName("asks the destination check before opening a socket")
    void checksDestinationBeforeConnecting() {
        // The order is the control. Checking after connecting still makes the
        // request the check exists to prevent.
        AtomicInteger requests = new AtomicInteger();
        serve("/doc", exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, 2, "hi".getBytes(StandardCharsets.UTF_8));
        });
        DestinationCheck refuse = target -> {
            throw new FetchNotPermittedException("no");
        };
        FetchProperties properties = new FetchProperties();
        var refusing = new RemoteContentFetcher(
            HttpClient.newHttpClient(), refuse, properties);

        assertThatThrownBy(() -> refusing.fetchTo(baseUri.resolve("/doc"), destination()))
            .isInstanceOf(FetchNotPermittedException.class);

        assertThat(requests).hasValue(0);
    }

    @Test
    @DisplayName("keeps the far end's declared filename, for display only")
    void reportsDeclaredFileName() {
        byte[] body = "x".getBytes(StandardCharsets.UTF_8);
        serve("/named", exchange -> {
            exchange.getResponseHeaders()
                .add("Content-Disposition", "attachment; filename=\"site-plan.pdf\"");
            respond(exchange, 200, body.length, body);
        });

        var fetched = fetcher().fetchTo(baseUri.resolve("/named"), destination());

        assertThat(fetched.declaredFileName()).isEqualTo("site-plan.pdf");
    }

    @Test
    @DisplayName("reports no filename rather than inventing one when none is offered")
    void reportsNoFileNameWhenAbsent() {
        byte[] body = "x".getBytes(StandardCharsets.UTF_8);
        serve("/plain", exchange -> respond(exchange, 200, body.length, body));

        var fetched = fetcher().fetchTo(baseUri.resolve("/plain"), destination());

        assertThat(fetched.declaredFileName()).isEmpty();
    }

    @Test
    @DisplayName("reports a refusal the integrator can act on when the host is unreachable")
    void reportsUnreachableHost() {
        server.stop(0);

        assertThatThrownBy(() -> fetcher().fetchTo(baseUri.resolve("/doc"), destination()))
            .isInstanceOf(ContentFetchFailedException.class)
            .hasMessageContaining("could not be read");
    }
}
