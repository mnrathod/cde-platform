package com.cde.platform.conversion;

import com.cde.platform.model.ConversionJob;
import com.cde.platform.repository.ConversionJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Moves a job through its states, one short transaction at a time.
 *
 * <p>Separate from {@link ConversionJobService} because the lifetimes are
 * different: submission is one transaction on a request thread, while a
 * conversion runs for minutes and must not hold a transaction open across the
 * fetch, the scan or the converter call (§7.5). Each method here opens a
 * transaction, makes one move, and closes it.
 *
 * <p>The caller supplies tenant context. There is no default and no fallback:
 * a worker with none reads nothing at all, which is the safe direction (§5.6).
 */
public class ConversionJobStateWriter {

    private static final Logger log = LoggerFactory.getLogger(ConversionJobStateWriter.class);

    private final ConversionJobRepository jobs;

    public ConversionJobStateWriter(ConversionJobRepository jobs) {
        this.jobs = jobs;
    }

    @Transactional
    public void markRunning(UUID publicId) {
        update(publicId, ConversionJob::begin);
    }

    @Transactional
    public void recordProgress(UUID publicId, int percent) {
        update(publicId, job -> job.reportProgress(percent));
    }

    @Transactional
    public void recordSourceFileName(UUID publicId, String sanitisedName) {
        update(publicId, job -> job.recordSourceFileName(sanitisedName));
    }

    @Transactional
    public void markSucceeded(UUID publicId, String resultObjectId, long sizeBytes) {
        update(publicId, job -> job.succeed(resultObjectId, sizeBytes));
    }

    /**
     * @param reason written for whoever submitted the job: what happened and
     *               what to do next (§1.4). Never a stack trace, an internal
     *               address, or a resolved address.
     */
    @Transactional
    public void markFailed(UUID publicId, String reason) {
        update(publicId, job -> {
            if (!job.isTerminal()) {
                job.fail(reason);
            }
        });
    }

    @Transactional
    public void markCancelled(UUID publicId) {
        update(publicId, job -> {
            if (!job.isTerminal()) {
                job.cancel();
            }
        });
    }

    @Transactional(readOnly = true)
    public boolean isCancellationRequested(UUID publicId) {
        return jobs.findByPublicId(publicId)
            .map(ConversionJob::isCancellationRequested)
            .orElse(false);
    }

    @Transactional(readOnly = true)
    public Optional<ConversionJob> find(UUID publicId) {
        return jobs.findByPublicId(publicId);
    }

    /**
     * A job that has vanished is logged rather than thrown for.
     *
     * <p>It means the row was deleted while the work ran — tenant offboarding
     * is the realistic cause — and there is nothing useful for a worker to do
     * about it. Throwing would turn a tidy outcome into a stack trace in the
     * logs of every job that outlived its tenant.
     */
    private void update(UUID publicId, java.util.function.Consumer<ConversionJob> change) {
        Optional<ConversionJob> job = jobs.findByPublicId(publicId);
        if (job.isEmpty()) {
            log.info("Conversion job {} no longer exists; abandoning the update", publicId);
            return;
        }
        change.accept(job.get());
        jobs.save(job.get());
    }
}
