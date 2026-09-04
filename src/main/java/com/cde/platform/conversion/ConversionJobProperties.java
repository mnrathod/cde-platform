package com.cde.platform.conversion;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Bounds on the conversion queue.
 *
 * <p>Every value here exists because the alternative is unbounded, and an
 * unbounded queue is a memory leak with a submit button.
 */
@ConfigurationProperties(prefix = "cde.conversion")
@Validated
public class ConversionJobProperties {

    /**
     * How many conversions run at once.
     *
     * <p>Small on purpose. Each one holds a fetched file and a converted file
     * on disk and occupies a converter slot, so raising this raises disk and
     * converter pressure rather than throughput.
     */
    @Min(1)
    private int workers = 4;

    /**
     * How many jobs may be waiting.
     *
     * <p>When it is full, submission is refused with 429 and a Retry-After
     * rather than queued. Refusing is the honest answer: accepting work the
     * system cannot get to produces a job that sits at PENDING until it is
     * failed by a restart, which looks like a bug to whoever submitted it.
     */
    @Min(1)
    private int queueCapacity = 256;

    /**
     * The most conversions one tenant may have running at once.
     *
     * <p>Noisy-neighbour protection (§7.8). Without it a tenant submitting a
     * hundred models occupies every worker and every other tenant's jobs wait
     * behind them.
     */
    @Min(1)
    private int maxConcurrentPerTenant = 2;

    /**
     * How long one conversion may take at the converter.
     *
     * <p>Generous, because the file it is converting may legitimately be
     * enormous, and separate from the fetch timeouts because they bound
     * different things.
     */
    @NotNull
    private Duration conversionTimeout = Duration.ofMinutes(30);

    public int getWorkers() {
        return workers;
    }

    public void setWorkers(int workers) {
        this.workers = workers;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public int getMaxConcurrentPerTenant() {
        return maxConcurrentPerTenant;
    }

    public void setMaxConcurrentPerTenant(int maxConcurrentPerTenant) {
        this.maxConcurrentPerTenant = maxConcurrentPerTenant;
    }

    public Duration getConversionTimeout() {
        return conversionTimeout;
    }

    public void setConversionTimeout(Duration conversionTimeout) {
        this.conversionTimeout = conversionTimeout;
    }
}
