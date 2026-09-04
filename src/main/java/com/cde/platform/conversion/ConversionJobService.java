package com.cde.platform.conversion;

import com.cde.platform.fetch.DestinationCheck;
import com.cde.platform.model.ConversionJob;
import com.cde.platform.model.ConversionJob.TargetFormat;
import com.cde.platform.repository.ConversionJobRepository;
import com.cde.platform.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Submitting, reading and cancelling conversions.
 *
 * <p>Submission does three things and stops: check the destination, write a
 * PENDING row, hand the request to the queue. That is what keeps it inside
 * §7.1's one-second budget — the work itself scales with the file, so it
 * cannot be on this path at all.
 *
 * <p>The destination is checked <em>here</em>, synchronously, rather than in
 * the worker. A URL that will never be fetched should be refused while the
 * caller is still on the phone: telling them at submission that their link is
 * not permitted is a 422 they can act on, where discovering it thirty seconds
 * later is a failed job they have to go and read. The worker's fetcher checks
 * again regardless, because the check belongs to the fetch and not to whoever
 * remembered to call it first.
 */
public class ConversionJobService {

    private static final Logger log = LoggerFactory.getLogger(ConversionJobService.class);

    private final ConversionJobRepository jobs;
    private final ConversionWorkQueue queue;
    private final DestinationCheck destinationCheck;

    public ConversionJobService(ConversionJobRepository jobs,
                                ConversionWorkQueue queue,
                                DestinationCheck destinationCheck) {
        this.jobs = jobs;
        this.queue = queue;
        this.destinationCheck = destinationCheck;
    }

    /**
     * Accepts a conversion and returns immediately.
     *
     * @throws com.cde.platform.fetch.FetchNotPermittedException if the link may
     *         not be fetched at all
     * @throws ConversionQueueFullException if the system has no room for it
     */
    @Transactional
    public ConversionJob submit(URI sourceUrl, TargetFormat targetFormat, long submittedBy) {
        destinationCheck.check(sourceUrl);

        long tenantId = TenantContext.requireTenantId();
        UUID publicId = UUID.randomUUID();

        ConversionJob job = jobs.save(ConversionJob.submitted(
            publicId, submittedBy, hostOf(sourceUrl), targetFormat));

        // Enqueued after the row exists, so a worker that picks it up
        // immediately finds something to update. The reverse order races: a
        // fast worker would look for a job that has not been written yet.
        queue.enqueue(new ConversionRequest(tenantId, publicId, sourceUrl));

        log.info("Accepted conversion job {} for tenant {} from host {}",
                 publicId, tenantId, job.getSourceHost());
        return job;
    }

    @Transactional(readOnly = true)
    public Optional<ConversionJob> findByPublicId(UUID publicId) {
        return jobs.findByPublicId(publicId);
    }

    @Transactional(readOnly = true)
    public Page<ConversionJob> list(Pageable pageable) {
        return jobs.findAllByOrderByCreatedAtDesc(pageable);
    }

    /**
     * Asks a running job to stop.
     *
     * @return empty when there is no such job for this tenant — which is also
     *         the answer for another tenant's job, because RLS filters it out
     *         before this sees a row and a caller must not be able to tell the
     *         two apart
     */
    @Transactional
    public Optional<ConversionJob> requestCancellation(UUID publicId) {
        return jobs.findByPublicId(publicId).map(job -> {
            // Racing a job to completion is normal rather than a client
            // mistake, so a job that already finished answers with its state
            // instead of an error.
            job.requestCancellation();
            return jobs.save(job);
        });
    }

    /**
     * Fails every job left mid-flight by a restart.
     *
     * <p>They cannot be resumed: the source link was never stored, because it
     * is a presigned bearer credential. Failing them with that reason is the
     * honest outcome — a caller polling one otherwise sees RUNNING for ever.
     *
     * <p>Runs per tenant because the query is tenant-scoped by RLS, which is
     * the point: a sweep written to see everything would see everything.
     */
    @Transactional
    public int failInterruptedJobsForCurrentTenant() {
        List<ConversionJob> interrupted = jobs.findByStatusIn(
            List.of(ConversionJob.Status.PENDING, ConversionJob.Status.RUNNING));

        interrupted.forEach(job -> job.fail(
            "This conversion was interrupted by a restart and cannot be resumed, "
            + "because the download link is never stored. Submit it again with a "
            + "fresh link."));
        jobs.saveAll(interrupted);

        return interrupted.size();
    }

    /**
     * The host, for the job record. Never the path or query string — the query
     * string is where a presigned URL keeps its signature.
     */
    private String hostOf(URI sourceUrl) {
        String host = sourceUrl.getHost();
        return host == null ? "" : host;
    }
}
