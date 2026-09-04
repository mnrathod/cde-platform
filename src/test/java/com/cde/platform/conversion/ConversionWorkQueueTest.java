package com.cde.platform.conversion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Whether one tenant can take the whole queue.
 *
 * <p>That is the property worth testing here. Ordering within a tenant is
 * ordinary FIFO and would work by accident; what would not work by accident is
 * that a tenant submitting a hundred models leaves room for everyone else, and
 * it is the kind of thing that only shows up in production as "the system is
 * slow for everyone but one customer".
 */
class ConversionWorkQueueTest {

    private static ConversionJobProperties properties(int capacity, int perTenant) {
        ConversionJobProperties properties = new ConversionJobProperties();
        properties.setQueueCapacity(capacity);
        properties.setMaxConcurrentPerTenant(perTenant);
        return properties;
    }

    private static ConversionRequest requestFor(long tenantId) {
        return new ConversionRequest(
            tenantId, UUID.randomUUID(), URI.create("https://files.example.test/doc"));
    }

    private static List<ConversionRequest> drain(ConversionWorkQueue queue, int count) {
        List<ConversionRequest> taken = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            taken.add(takeOrFail(queue));
        }
        return taken;
    }

    /**
     * Takes on another thread, so a queue that wrongly has nothing to give
     * fails the test instead of hanging it.
     *
     * <p>An earlier version called {@code queue.take()} directly. When a
     * mutation stopped {@code completed} freeing a slot, the test did not fail
     * — it blocked for ever, and took the build with it. A blocking call in a
     * test needs a deadline for the same reason a network call does.
     */
    private static Optional<ConversionRequest> takeWithin(ConversionWorkQueue queue,
                                                          Duration limit) {
        ExecutorService thread = Executors.newSingleThreadExecutor(runnable -> {
            Thread daemon = new Thread(runnable, "queue-take");
            daemon.setDaemon(true);
            return daemon;
        });
        try {
            Future<Optional<ConversionRequest>> pending = thread.submit(queue::take);
            return pending.get(limit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Blocked, which for some tests is the expected answer.
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while taking from the queue", e);
        } catch (ExecutionException e) {
            throw new AssertionError(e.getCause());
        } finally {
            thread.shutdownNow();
        }
    }

    private static ConversionRequest takeOrFail(ConversionWorkQueue queue) {
        Optional<ConversionRequest> taken = takeWithin(queue, Duration.ofSeconds(5));
        assertThat(taken)
            .as("the queue should have had work to give")
            .isNotNull().isPresent();
        return taken.orElseThrow();
    }

    @Test
    @DisplayName("one tenant cannot occupy more than its share of the workers")
    void oneTenantCannotTakeEveryWorker() {
        // The whole reason this is not a single FIFO — and it has to be
        // asserted by asking for one MORE than the limit and finding nothing
        // there. An earlier version of this test drained exactly the limit and
        // then checked the limit had been reached, which is true whether or not
        // the limit exists: it passed with the check deleted.
        ConversionWorkQueue queue = new ConversionWorkQueue(properties(100, 2));
        for (int i = 0; i < 10; i++) {
            queue.enqueue(requestFor(7L));
        }

        assertThat(drain(queue, 2)).hasSize(2);

        assertThat(takeWithin(queue, Duration.ofMillis(400)))
            .as("eight jobs are still waiting, but tenant 7 already holds its "
                + "two slots, so a third worker must find nothing to do")
            .isNull();
        assertThat(queue.runningCount(7L)).isEqualTo(2);
        assertThat(queue.waitingCount()).isEqualTo(8);
    }

    @Test
    @DisplayName("a tenant at its limit does not block another tenant behind it")
    void aSaturatedTenantDoesNotBlockOthers() {
        // Ten jobs from tenant 7 queued first, tenant 9's single job last.
        // With a limit of one, tenant 7 can hold only one slot, so the only way
        // a second worker finds anything is by skipping past nine waiting
        // tenant-7 jobs to reach tenant 9.
        ConversionWorkQueue queue = new ConversionWorkQueue(properties(100, 1));
        for (int i = 0; i < 10; i++) {
            queue.enqueue(requestFor(7L));
        }
        queue.enqueue(requestFor(9L));

        List<Long> served = drain(queue, 2).stream()
            .map(ConversionRequest::tenantId).toList();

        assertThat(served).containsExactly(7L, 9L);
    }

    @Test
    @DisplayName("takes a turn for each tenant rather than draining one first")
    void rotatesBetweenTenants() {
        // Pins the rotation on its own, with the concurrency limit set high
        // enough that it cannot be what produces the answer. Without rotation
        // both takes come from tenant 7, which has plenty of slots left.
        ConversionWorkQueue queue = new ConversionWorkQueue(properties(100, 10));
        for (int i = 0; i < 5; i++) {
            queue.enqueue(requestFor(7L));
        }
        queue.enqueue(requestFor(9L));

        List<Long> served = drain(queue, 2).stream()
            .map(ConversionRequest::tenantId).toList();

        assertThat(served).containsExactly(7L, 9L);
    }

    @Test
    @DisplayName("finishing one job frees that tenant's slot for the next")
    void completingFreesASlot() {
        ConversionWorkQueue queue = new ConversionWorkQueue(properties(100, 1));
        queue.enqueue(requestFor(7L));
        queue.enqueue(requestFor(7L));

        takeOrFail(queue);
        assertThat(queue.runningCount(7L)).isEqualTo(1);

        // At the limit, so the second job is unreachable until the first ends.
        assertThat(takeWithin(queue, Duration.ofMillis(400)))
            .as("tenant 7 holds its only slot")
            .isNull();

        queue.completed(7L);

        assertThat(takeWithin(queue, Duration.ofSeconds(5)))
            .as("the slot is free again")
            .isNotNull().isPresent();
        assertThat(queue.runningCount(7L)).isEqualTo(1);
    }

    @Test
    @DisplayName("refuses a submission rather than queueing work it cannot reach")
    void refusesWhenFull() {
        // Accepting past capacity produces a job that sits at PENDING until a
        // restart fails it, which reads as a bug to whoever submitted it.
        ConversionWorkQueue queue = new ConversionWorkQueue(properties(2, 4));
        queue.enqueue(requestFor(7L));
        queue.enqueue(requestFor(7L));

        assertThatThrownBy(() -> queue.enqueue(requestFor(7L)))
            .isInstanceOf(ConversionQueueFullException.class)
            .satisfies(thrown -> assertThat(
                ((ConversionQueueFullException) thrown).getRetryAfterSeconds())
                .isPositive());
    }

    @Test
    @DisplayName("counts capacity across tenants, not per tenant")
    void capacityIsGlobal() {
        // Otherwise the cap is capacity × number of tenants, and a caller can
        // raise it by registering more tenants.
        ConversionWorkQueue queue = new ConversionWorkQueue(properties(2, 4));
        queue.enqueue(requestFor(7L));
        queue.enqueue(requestFor(9L));

        assertThatThrownBy(() -> queue.enqueue(requestFor(11L)))
            .isInstanceOf(ConversionQueueFullException.class);
    }

    @Test
    @DisplayName("a worker waits rather than spinning when there is nothing to do")
    void takeBlocksUntilWorkArrives() throws InterruptedException {
        ConversionWorkQueue queue = new ConversionWorkQueue(properties(10, 2));
        CountDownLatch waiting = new CountDownLatch(1);
        AtomicReference<ConversionRequest> taken = new AtomicReference<>();

        Thread worker = new Thread(() -> {
            try {
                waiting.countDown();
                queue.take().ifPresent(taken::set);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        worker.start();

        assertThat(waiting.await(2, TimeUnit.SECONDS)).isTrue();
        queue.enqueue(requestFor(7L));
        worker.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(taken.get()).isNotNull();
        assertThat(taken.get().tenantId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("a waiting worker is released on shutdown rather than hanging")
    void shutdownReleasesWaitingWorkers() throws InterruptedException {
        // Without this the JVM would not stop cleanly, and a rolling deploy
        // would wait out the termination grace period on every pod.
        ConversionWorkQueue queue = new ConversionWorkQueue(properties(10, 2));
        AtomicReference<Boolean> returnedEmpty = new AtomicReference<>();

        Thread worker = new Thread(() -> {
            try {
                returnedEmpty.set(queue.take().isEmpty());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        worker.start();
        Thread.sleep(100);

        queue.shutDown();
        worker.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(returnedEmpty.get()).isTrue();
    }

    @Test
    @DisplayName("refuses new work once shutting down")
    void refusesAfterShutdown() {
        ConversionWorkQueue queue = new ConversionWorkQueue(properties(10, 2));
        queue.shutDown();

        assertThatThrownBy(() -> queue.enqueue(requestFor(7L)))
            .isInstanceOf(ConversionQueueFullException.class);
    }

    @Test
    @DisplayName("serves one tenant's jobs in the order they were submitted")
    void fifoWithinATenant() {
        ConversionWorkQueue queue = new ConversionWorkQueue(properties(10, 4));
        ConversionRequest first = requestFor(7L);
        ConversionRequest second = requestFor(7L);
        queue.enqueue(first);
        queue.enqueue(second);

        List<UUID> order = drain(queue, 2).stream()
            .map(ConversionRequest::jobPublicId).toList();

        assertThat(order).containsExactly(first.jobPublicId(), second.jobPublicId());
    }

    @Test
    @DisplayName("never prints the source link")
    void requestDoesNotLeakTheCredential() {
        // A presigned URL is a bearer credential, and the commonest way one
        // reaches a log is an object interpolated into a message rather than a
        // deliberate statement about it.
        ConversionRequest request = new ConversionRequest(
            7L, UUID.randomUUID(),
            URI.create("https://files.example.test/doc?sig=synthetic-not-a-real-signature"));

        assertThat(request.toString())
            .doesNotContain("sig=")
            .doesNotContain("synthetic-not-a-real-signature")
            .contains("withheld");
    }
}
