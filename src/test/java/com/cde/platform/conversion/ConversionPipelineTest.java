package com.cde.platform.conversion;

import com.cde.platform.fetch.DestinationCheck;
import com.cde.platform.fetch.FetchProperties;
import com.cde.platform.fetch.RemoteContentFetcher;
import com.cde.platform.model.ConversionJob;
import com.cde.platform.model.ConversionJob.Status;
import com.cde.platform.model.ConversionJob.TargetFormat;
import com.cde.platform.model.Tenant;
import com.cde.platform.model.User;
import com.cde.platform.repository.ConversionJobRepository;
import com.cde.platform.repository.TenantRepository;
import com.cde.platform.repository.UserRepository;
import com.cde.platform.service.ConverterService;
import com.cde.platform.service.DxfToSvgService;
import com.cde.platform.storage.StorageCategory;
import com.cde.platform.storage.StorageKey;
import com.cde.platform.storage.StorageProperties;
import com.cde.platform.storage.StorageProvider;
import com.cde.platform.tenancy.TenantContext;
import com.cde.platform.upload.UploadAdmissionService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pipeline actually running, from a link to a stored PDF.
 *
 * <p>Everything here is real except the two ends: the file and the converter
 * are served by a loopback HTTP server, and the destination check is permissive
 * so that loopback can be reached — which the real policy refuses on purpose,
 * and goes on refusing, since that judgement has its own tests.
 *
 * <p>Everything between those ends is the production object. The fetcher
 * streams and enforces its caps, admission inspects the magic bytes and
 * consults the scanner policy, the converter client posts and streams the
 * reply to disk, storage writes under a tenant-prefixed key, and the state
 * writer moves the job through the same transactions the workers use. Stubbing
 * those would leave the ordering tested and nothing else — and the ordering is
 * the least likely thing to be wrong.
 */
@SpringBootTest
// The pipeline's collaborators are conditional on fetching being enabled; a
// context without them has no pipeline to test.
@org.springframework.test.context.TestPropertySource(properties = "cde.fetch.enabled=true")
class ConversionPipelineTest {

    /** Enough of a PDF for magic-byte detection to call it one. */
    private static final byte[] A_PDF =
        "%PDF-1.7\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF"
            .getBytes(StandardCharsets.UTF_8);

    private static final byte[] A_CONVERTED_PDF =
        "%PDF-1.7\n% converted\ntrailer<<>>\n%%EOF".getBytes(StandardCharsets.UTF_8);

    @Autowired ConversionJobRepository jobs;
    @Autowired ConversionJobStateWriter stateWriter;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired UploadAdmissionService admission;
    @Autowired StorageProvider storage;
    @Autowired StorageProperties storageProperties;
    @Autowired DxfToSvgService dxfFallback;

    @Value("${cde.storage.upload-dir}") String uploadDir;

    private HttpServer server;
    private URI baseUri;
    private final AtomicInteger conversionRequests = new AtomicInteger();

    private long tenantId;
    private long userId;

    @BeforeEach
    void startServerAndSeedTenant() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/source.pdf", exchange -> respond(exchange, 200, A_PDF,
            "application/pdf", "attachment; filename=\"site-plan.pdf\""));
        server.createContext("/convert", exchange -> {
            conversionRequests.incrementAndGet();
            respond(exchange, 200, A_CONVERTED_PDF, "application/pdf", null);
        });
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());

        tenantId = tenants.save(Tenant.builder()
            .slug("pipeline-" + System.nanoTime())
            .name("Pipeline test tenant")
            .build()).getId();
        userId = TenantContext.callAsTenant(tenantId, () -> users.save(User.builder()
            .username("pipeline-" + tenantId)
            .email("pipeline-" + tenantId + "@example.test")
            .password("{noop}irrelevant")
            .role(User.Role.ENGINEER)
            .build()).getId());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static void respond(HttpExchange exchange, int status, byte[] body,
                                String contentType, String disposition) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        if (disposition != null) {
            exchange.getResponseHeaders().add("Content-Disposition", disposition);
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /** The real pipeline, with only the destination check relaxed. */
    private ConversionPipeline pipeline() {
        FetchProperties fetchProperties = new FetchProperties();
        DestinationCheck permitLoopback = target -> { };
        RemoteContentFetcher fetcher = new RemoteContentFetcher(
            HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build(),
            permitLoopback, fetchProperties);

        ConverterService converter = new ConverterService(baseUri.toString(), dxfFallback);

        return new ConversionPipeline(fetcher, admission, converter, storage, stateWriter,
                                      new ConversionJobProperties(), storageProperties,
                                      uploadDir);
    }

    private UUID seedPendingJob() {
        return TenantContext.callAsTenant(tenantId, () -> {
            UUID publicId = UUID.randomUUID();
            jobs.save(ConversionJob.submitted(
                publicId, userId, "127.0.0.1", TargetFormat.PDF));
            return publicId;
        });
    }

    private ConversionJob reload(UUID publicId) {
        return TenantContext.callAsTenant(tenantId,
            () -> jobs.findByPublicId(publicId).orElseThrow());
    }

    private Path workDirectoryFor(UUID jobId) {
        return Path.of(uploadDir).resolve("conversion")
                   .resolve(String.valueOf(tenantId)).resolve(jobId.toString());
    }

    @Test
    @DisplayName("fetches, admits, converts and stores, leaving a downloadable result")
    void runsToSuccess() throws IOException {
        UUID jobId = seedPendingJob();

        pipeline().run(new ConversionRequest(tenantId, jobId, baseUri.resolve("/source.pdf")));

        ConversionJob finished = reload(jobId);
        assertThat(finished.getStatus()).isEqualTo(Status.SUCCEEDED);
        assertThat(finished.getProgressPercent()).isEqualTo((short) 100);
        assertThat(finished.getResultSizeBytes()).contains((long) A_CONVERTED_PDF.length);
        assertThat(finished.getFinishedAt()).isPresent();
        assertThat(conversionRequests).hasValue(1);

        // The stored object is reachable under the job's own tenant prefix and
        // holds what the converter produced — not the original.
        StorageKey key = new StorageKey(storageProperties.getEnvironment(), tenantId,
            StorageCategory.DERIVATIVE, finished.getResultObjectId().orElseThrow());
        try (InputStream stored = storage.retrieve(key)) {
            assertThat(stored.readAllBytes()).isEqualTo(A_CONVERTED_PDF);
        }
    }

    @Test
    @DisplayName("keeps the far end's filename, sanitised, for display")
    void recordsTheDeclaredFileName() {
        UUID jobId = seedPendingJob();

        pipeline().run(new ConversionRequest(tenantId, jobId, baseUri.resolve("/source.pdf")));

        assertThat(reload(jobId).getSourceFileName()).contains("site-plan");
    }

    @Test
    @DisplayName("deletes the fetched original and the converted copy when it is done")
    void clearsItsWorkArea() {
        // Otherwise the work area grows at the rate documents are submitted,
        // holding two copies of every one of them.
        UUID jobId = seedPendingJob();

        pipeline().run(new ConversionRequest(tenantId, jobId, baseUri.resolve("/source.pdf")));

        assertThat(workDirectoryFor(jobId)).doesNotExist();
    }

    @Test
    @DisplayName("stops before converting when cancellation was asked for")
    void stopsWhenCancelled() {
        UUID jobId = seedPendingJob();
        TenantContext.runAsTenant(tenantId, () -> {
            ConversionJob job = jobs.findByPublicId(jobId).orElseThrow();
            job.requestCancellation();
            jobs.save(job);
        });

        pipeline().run(new ConversionRequest(tenantId, jobId, baseUri.resolve("/source.pdf")));

        assertThat(reload(jobId).getStatus()).isEqualTo(Status.CANCELLED);
        assertThat(conversionRequests)
            .as("a cancelled job must not reach the converter")
            .hasValue(0);
    }

    @Test
    @DisplayName("fails with a readable reason when the source is not there")
    void failsWhenSourceIsMissing() {
        UUID jobId = seedPendingJob();

        pipeline().run(new ConversionRequest(tenantId, jobId, baseUri.resolve("/nothing-here")));

        ConversionJob failed = reload(jobId);
        assertThat(failed.getStatus()).isEqualTo(Status.FAILED);
        assertThat(failed.getFailureReason())
            .as("the reason must be something the submitter can act on")
            .isPresent();
        assertThat(failed.getFailureReason().orElseThrow())
            .doesNotContain("Exception")
            .doesNotContain("com.cde.platform");
    }

    @Test
    @DisplayName("leaves nothing behind when it fails either")
    void clearsItsWorkAreaOnFailure() {
        UUID jobId = seedPendingJob();

        pipeline().run(new ConversionRequest(tenantId, jobId, baseUri.resolve("/nothing-here")));

        assertThat(workDirectoryFor(jobId)).doesNotExist();
    }

    @Test
    @DisplayName("never throws, whatever happens, so a worker cannot lose its thread")
    void neverThrows() {
        // A pipeline that threw would leave the job at RUNNING for ever and
        // take a worker with it. The failure is deliberately one nothing
        // anticipates: a scheme the fetcher will not accept.
        UUID jobId = seedPendingJob();

        pipeline().run(new ConversionRequest(
            tenantId, jobId, URI.create("gopher://127.0.0.1/x")));

        assertThat(reload(jobId).getStatus()).isEqualTo(Status.FAILED);
    }

    @Test
    @DisplayName("writes the result under its own tenant's prefix")
    void resultIsTenantPrefixed() throws IOException {
        // The key is built from the request's tenant, so a result cannot land
        // in another tenant's prefix even if the job row said otherwise (§11).
        UUID jobId = seedPendingJob();

        pipeline().run(new ConversionRequest(tenantId, jobId, baseUri.resolve("/source.pdf")));

        String objectId = reload(jobId).getResultObjectId().orElseThrow();
        Path expected = Path.of(storageProperties.getLocalRoot())
            .resolve(storageProperties.getEnvironment())
            .resolve(String.valueOf(tenantId))
            .resolve(StorageCategory.DERIVATIVE.segment())
            .resolve(objectId);

        assertThat(Files.exists(expected))
            .as("expected the result at %s", expected)
            .isTrue();
    }
}
