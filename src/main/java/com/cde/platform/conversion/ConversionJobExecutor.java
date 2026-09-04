package com.cde.platform.conversion;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The threads that drain {@link ConversionWorkQueue}.
 *
 * <p>A fixed pool of named threads rather than anything cleverer. The work is
 * IO-bound and the limit on how much of it should run at once is disk and
 * converter pressure, not CPU, so more threads would buy contention rather
 * than throughput.
 *
 * <p>Each worker loops: take a request, run it, release the tenant's slot.
 * The release is in a {@code finally} because a missed one leaks a slot
 * permanently — after {@code maxConcurrentPerTenant} escapes that tenant could
 * never run anything again, and it would look like a hang rather than a bug.
 */
public class ConversionJobExecutor {

    private static final Logger log = LoggerFactory.getLogger(ConversionJobExecutor.class);

    private final ConversionWorkQueue queue;
    private final ConversionPipeline pipeline;
    private final ExecutorService workers;
    private final int workerCount;

    public ConversionJobExecutor(ConversionWorkQueue queue,
                                 ConversionPipeline pipeline,
                                 ConversionJobProperties properties) {
        this.queue = queue;
        this.pipeline = pipeline;
        this.workerCount = properties.getWorkers();

        AtomicInteger sequence = new AtomicInteger();
        this.workers = Executors.newFixedThreadPool(workerCount, runnable -> {
            // Named, because "pool-3-thread-2" in a thread dump during an
            // incident tells whoever is holding the pager nothing.
            Thread thread = new Thread(runnable, "conversion-worker-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Starts the workers.
     *
     * <p>Called from {@link ConversionStartupRecovery} after it has failed the
     * jobs a restart interrupted, so a worker cannot pick up and race a row
     * that recovery is about to rewrite.
     */
    public void start() {
        for (int worker = 0; worker < workerCount; worker++) {
            workers.submit(this::workUntilShutdown);
        }
        log.info("Started {} conversion workers", workerCount);
    }

    private void workUntilShutdown() {
        while (true) {
            ConversionRequest request;
            try {
                Optional<ConversionRequest> next = queue.take();
                if (next.isEmpty()) {
                    return;
                }
                request = next.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            try {
                pipeline.run(request);
            } catch (RuntimeException e) {
                // The pipeline is written not to throw — it turns every
                // failure into a job outcome. If one escapes anyway, losing
                // the worker to it would silently shrink the pool, so it is
                // logged and the loop continues.
                log.error("A conversion worker caught an escaped failure for job {}",
                          request.jobPublicId(), e);
            } finally {
                queue.completed(request.tenantId());
            }
        }
    }

    @PreDestroy
    public void stop() {
        queue.shutDown();
        workers.shutdown();
        try {
            // In-flight conversions get a moment to finish. Anything still
            // running past it is abandoned, and its job row is failed by
            // recovery on the next start — which is the same outcome as any
            // other interrupted job, and honest about what happened.
            if (!workers.awaitTermination(20, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
        }
    }
}
