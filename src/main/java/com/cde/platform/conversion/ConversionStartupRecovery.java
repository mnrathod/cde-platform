package com.cde.platform.conversion;

import com.cde.platform.model.Tenant;
import com.cde.platform.repository.TenantRepository;
import com.cde.platform.tenancy.TenantContext;
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
 * <p>The sweep runs once per tenant rather than once across the table. That is
 * not a stylistic choice: every query here is scoped by Row-Level Security to
 * {@code app.tenant_id}, and a sweep written to see every row would be a query
 * that sees every row (§5.6). Establishing context per tenant keeps the
 * recovery path under the same control as every other read.
 */
public class ConversionStartupRecovery {

    private static final Logger log = LoggerFactory.getLogger(ConversionStartupRecovery.class);

    private final ConversionJobService jobService;
    private final TenantRepository tenants;
    private final ConversionJobExecutor executor;

    public ConversionStartupRecovery(ConversionJobService jobService,
                                     TenantRepository tenants,
                                     ConversionJobExecutor executor) {
        this.jobService = jobService;
        this.tenants = tenants;
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
        for (Tenant tenant : tenants.findAll()) {
            try {
                total += TenantContext.callAsTenant(
                    tenant.getId(), jobService::failInterruptedJobsForCurrentTenant);
            } catch (RuntimeException e) {
                // One tenant's recovery failing must not stop the others', and
                // must not stop the workers from starting: the alternative is
                // an instance that boots and then converts nothing.
                log.error("Could not recover conversion jobs for tenant {}", tenant.getId(), e);
            }
        }
        return total;
    }
}
