package com.cde.platform.conversion;

import com.cde.platform.fetch.FetchDestinationPolicy;
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
 * <p>The parts of the destination check that need no DNS happen <em>here</em>,
 * synchronously: a wrong scheme, an unlisted host, or a literal address inside
 * our own network should be refused while the caller is still on the phone,
 * because that is a 422 they can act on where a failed job thirty seconds
 * later is something they have to go and read.
 *
 * <p>The address check for a host <em>name</em> stays with the fetch. It needs
 * a lookup, and a lookup on this path would put a network round trip inside a
 * §7.1 budget and would turn a momentary DNS failure into "your link is not
 * permitted" — which sends an integrator looking for a fault in a link that is
 * perfectly good. The fetcher resolves and checks every address before it
 * connects, which is where that check belongs regardless of who remembered to
 * call it first.
 */
public class ConversionJobService {

    private static final Logger log = LoggerFactory.getLogger(ConversionJobService.class);

    private final ConversionJobRepository jobs;
    private final ConversionWorkQueue queue;
    private final FetchDestinationPolicy destinationPolicy;

    public ConversionJobService(ConversionJobRepository jobs,
                                ConversionWorkQueue queue,
                                FetchDestinationPolicy destinationPolicy) {
        this.jobs = jobs;
        this.queue = queue;
        this.destinationPolicy = destinationPolicy;
    }

    /**
     * Accepts a conversion and returns immediately.
     *
     * @throws com.cde.platform.fetch.FetchNotPermittedException if the link may
     *         not be fetched at all
     * @throws ConversionQueueFullException if the system has no room for it
     */
    @Transactional
    public ConversionJob submit(URI sourceUrl, TargetFormat targetFormat, long submittedBy,
                                String idempotencyKey) {
        // Before the destination check, deliberately. A retry of a submission
        // that already succeeded should return the same job whatever the link
        // now looks like — a presigned URL expires, so re-checking it would
        // turn a safe retry into a refusal of work that is already running.
        Optional<ConversionJob> alreadySubmitted = findByIdempotencyKey(idempotencyKey);
        if (alreadySubmitted.isPresent()) {
            log.info("Returning existing conversion job {} for a repeated submission",
                     alreadySubmitted.get().getPublicId());
            return alreadySubmitted.get();
        }

        // Only what needs no DNS lookup. Resolving here would put a network
        // round trip inside a §7.1 budget, and would report a momentarily
        // unresolvable host as "not permitted" — telling an integrator their
        // link is wrong when the truth is "try again". The fetch resolves and
        // checks every address before it connects, which is where that check
        // has to be anyway.
        destinationPolicy.checkWhatNeedsNoLookup(sourceUrl);

        long tenantId = TenantContext.requireTenantId();
        UUID publicId = UUID.randomUUID();

        ConversionJob job = jobs.save(ConversionJob.submitted(
            publicId, submittedBy, hostOf(sourceUrl), targetFormat, blankToNull(idempotencyKey)));

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

    private Optional<ConversionJob> findByIdempotencyKey(String idempotencyKey) {
        String key = blankToNull(idempotencyKey);
        return key == null ? Optional.empty() : jobs.findByIdempotencyKey(key);
    }

    /**
     * An absent key and an empty one mean the same thing: no key.
     *
     * <p>Stored as null rather than {@code ""} so the partial unique index
     * ignores it. Storing empty strings would make every keyless submission
     * collide with every other one, turning an optional feature into an
     * outage.
     */
    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
