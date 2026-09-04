package com.cde.platform.conversion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Which conversion runs next, and how many one tenant may occupy.
 *
 * <p>A single FIFO would let one tenant submitting a hundred models fill every
 * worker while everyone else waits behind them (§7.8). So the queue is one
 * deque per tenant plus a rotation over the tenants that have work, and a
 * tenant already at its concurrency limit is skipped rather than blocking the
 * rotation. A tenant with a hundred jobs and a limit of two therefore holds two
 * workers, not all of them, however early they submitted.
 *
 * <p>Deliberately not a {@code PriorityQueue} on some fairness score. Round
 * robin over per-tenant queues is the boring implementation of the property
 * that actually matters — no tenant starves — and it can be read and reasoned
 * about at three in the morning.
 *
 * <p>All state is guarded by the monitor. The queue is small and the operations
 * are short; contention here would mean the workers had nothing to do.
 */
public class ConversionWorkQueue {

    private static final Logger log = LoggerFactory.getLogger(ConversionWorkQueue.class);

    private final int capacity;
    private final int maxConcurrentPerTenant;
    private final int retryAfterSeconds;

    private final Map<Long, Deque<ConversionRequest>> waitingByTenant = new HashMap<>();
    private final Map<Long, Integer> runningByTenant = new HashMap<>();
    private final Deque<Long> rotation = new ArrayDeque<>();
    private int waitingTotal;
    private boolean shuttingDown;

    public ConversionWorkQueue(ConversionJobProperties properties) {
        this.capacity = properties.getQueueCapacity();
        this.maxConcurrentPerTenant = properties.getMaxConcurrentPerTenant();
        this.retryAfterSeconds = 30;
    }

    /**
     * @throws ConversionQueueFullException when there is no room, so the caller
     *         is refused now rather than given a job that will never run
     */
    public synchronized void enqueue(ConversionRequest request) {
        if (shuttingDown) {
            throw new ConversionQueueFullException(retryAfterSeconds);
        }
        if (waitingTotal >= capacity) {
            log.warn("Refusing a conversion for tenant {}: {} already waiting",
                     request.tenantId(), waitingTotal);
            throw new ConversionQueueFullException(retryAfterSeconds);
        }

        waitingByTenant
            .computeIfAbsent(request.tenantId(), tenant -> new ArrayDeque<>())
            .addLast(request);
        waitingTotal++;
        if (!rotation.contains(request.tenantId())) {
            rotation.addLast(request.tenantId());
        }
        notifyAll();
    }

    /**
     * Blocks until there is work this caller may run, or the queue shuts down.
     *
     * @return empty only on shutdown
     */
    public synchronized Optional<ConversionRequest> take() throws InterruptedException {
        while (!shuttingDown) {
            Optional<ConversionRequest> next = pollEligible();
            if (next.isPresent()) {
                return next;
            }
            // Either nothing is waiting, or everything waiting belongs to
            // tenants already at their limit. Both are resolved by another
            // thread finishing, which notifies.
            wait();
        }
        return Optional.empty();
    }

    /**
     * The first waiting request whose tenant is below its concurrency limit,
     * rotating so the search does not always start with the same tenant.
     */
    private Optional<ConversionRequest> pollEligible() {
        for (int examined = 0; examined < rotation.size(); examined++) {
            Long tenantId = rotation.removeFirst();
            rotation.addLast(tenantId);

            Deque<ConversionRequest> waiting = waitingByTenant.get(tenantId);
            if (waiting == null || waiting.isEmpty()) {
                continue;
            }
            if (runningByTenant.getOrDefault(tenantId, 0) >= maxConcurrentPerTenant) {
                continue;
            }

            ConversionRequest request = waiting.removeFirst();
            waitingTotal--;
            runningByTenant.merge(tenantId, 1, Integer::sum);
            return Optional.of(request);
        }
        return Optional.empty();
    }

    /**
     * Records that a worker has finished, freeing a slot for that tenant.
     *
     * <p>Must be called for a failed job as well as a successful one. A missed
     * call leaks a concurrency slot permanently, and after
     * {@code maxConcurrentPerTenant} failures that tenant can never run
     * anything again — which is why every caller does this in a finally block.
     */
    public synchronized void completed(long tenantId) {
        runningByTenant.computeIfPresent(
            tenantId, (tenant, running) -> running <= 1 ? null : running - 1);
        cleanUp(tenantId);
        notifyAll();
    }

    private void cleanUp(long tenantId) {
        Deque<ConversionRequest> waiting = waitingByTenant.get(tenantId);
        boolean idle = (waiting == null || waiting.isEmpty())
                    && !runningByTenant.containsKey(tenantId);
        if (idle) {
            waitingByTenant.remove(tenantId);
            rotation.remove(tenantId);
        }
    }

    public synchronized int waitingCount() {
        return waitingTotal;
    }

    public synchronized int runningCount(long tenantId) {
        return runningByTenant.getOrDefault(tenantId, 0);
    }

    /** Stops accepting work and releases every waiting worker. */
    public synchronized void shutDown() {
        shuttingDown = true;
        notifyAll();
    }
}
