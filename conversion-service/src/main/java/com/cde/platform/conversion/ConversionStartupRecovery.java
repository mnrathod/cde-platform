package com.cde.platform.conversion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * Settles the jobs a restart interrupted, then lets the workers start.
 *
 * <p>A job that was PENDING or RUNNING when the process stopped cannot be
 * resumed: its source link was never stored, because a presigned URL is a
 * bearer credential. Leaving those rows alone would leave a caller polling a
 * job that says RUNNING for ever, which is the worst of the available
 * outcomes — it is not merely wrong, it never resolves.
 *
 * <p>So they are failed with that reason, which a submitter can act on: get a
 * fresh link and submit again. A presigned URL typically expires within
 * fifteen minutes anyway, so one that waited through a restart would very
 * likely have expired regardless.
 *
 * <p>The sweep runs once per caller rather than once across the table. That is
 * not a stylistic choice: the host scopes every query here to the caller in
 * context — in this platform by Row-Level Security on {@code app.tenant_id}
 * (§5.6) — so a sweep written to see every row would have to be a query that
 * sees every row, which is precisely the query that isolation exists to
 * prevent. Going through {@link ConversionCallers#callAsCaller} keeps the
 * recovery path under whatever control the host applies to every other read,
 * without this class needing to know what that control is.
 */
public class ConversionStartupRecovery {

    private static final Logger log = LoggerFactory.getLogger(ConversionStartupRecovery.class);

    private final ConversionJobService jobService;
    private final ConversionCallers callers;
    private final ConversionJobExecutor executor;

    public ConversionStartupRecovery(ConversionJobService jobService,
                                     ConversionCallers callers,
                                     ConversionJobExecutor executor) {
        this.jobService = jobService;
        this.callers = callers;
        this.executor = executor;
    }

    /**
     * Recovery first, workers second.
     *
     * <p>The order matters. A worker started first could pick up a freshly
     * submitted job and be part-way through it while the sweep decides that
     * anything RUNNING must be stale — and fail a job that is running
     * perfectly well.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverThenStartWorkers() {
        int failed = failInterruptedJobs();
        if (failed > 0) {
            log.warn("Failed {} conversion job(s) left unfinished by a restart", failed);
        }
        executor.start();
    }

    private int failInterruptedJobs() {
        int total = 0;
        for (Long callerId : callers.knownCallerIds()) {
            try {
                total += callers.callAsCaller(
                    callerId, jobService::failInterruptedJobsForCurrentCaller);
            } catch (RuntimeException e) {
                // One caller's recovery failing must not stop the others', and
                // must not stop the workers from starting: the alternative is
                // an instance that boots and then converts nothing.
                log.error("Could not recover conversion jobs for caller {}", callerId, e);
            }
        }
        return total;
    }
}
