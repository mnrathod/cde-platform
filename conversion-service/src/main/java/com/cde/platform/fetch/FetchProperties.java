package com.cde.platform.fetch;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Bounds on fetching a URL an integrating application supplied.
 *
 * <p>Every value here exists because the alternative is unbounded, and this is
 * the one code path where a caller chooses what this server connects to. An
 * unbounded fetch is a denial of service that costs the caller one request:
 * point us at something that streams forever and walk away.
 */
@ConfigurationProperties(prefix = "cde.fetch")
@Validated
public class FetchProperties {

    /**
     * Whether integrator-supplied URLs may be fetched at all.
     *
     * <p>Off is a legitimate production posture, not a degraded one: an
     * air-gapped or PROTECTED deployment (§6.4–6.6) prohibits outbound calls
     * outright, and those deployments push content to the upload endpoint
     * instead. Defaulting to on would make the sovereign case the one that
     * has to remember.
     */
    private boolean enabled = false;

    /**
     * Refuse {@code http}, permitting only {@code https}.
     *
     * <p>On by default and worth leaving on: a presigned link carries its own
     * authorisation in the query string, so plain http hands that credential
     * to anything on the path.
     */
    private boolean requireTls = true;

    /**
     * Host names that may be fetched. Empty permits any host that passes the
     * address rules in {@link FetchDestinationPolicy}.
     *
     * <p>A deployment that can name its storage hosts should. The address
     * rules stop us reaching our own network; an allow-list additionally stops
     * us being pointed at somebody else's, and it is the only thing that fully
     * closes the DNS rebinding window described on
     * {@link RemoteContentFetcher}.
     */
    private List<String> permittedHosts = new ArrayList<>();

    /**
     * The largest response body accepted, enforced as a running total during
     * the transfer rather than trusted from {@code Content-Length}.
     *
     * <p>Two gigabytes, matching the chunked-upload ceiling, because a
     * federated model is the case this exists for and the two paths should not
     * disagree about how large a document may be.
     */
    @NotNull
    private DataSize maxContentSize = DataSize.ofGigabytes(2);

    /** How long to wait for the connection itself. */
    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(10);

    /** How long to wait for response headers after connecting. */
    @NotNull
    private Duration responseTimeout = Duration.ofSeconds(30);

    /**
     * How long the whole transfer may take.
     *
     * <p>Separate from {@link #responseTimeout} because they stop different
     * attacks. A response timeout is satisfied the moment headers arrive, so a
     * server that sends headers promptly and then dribbles one byte a minute
     * passes it and holds a thread and a disk allocation indefinitely. This is
     * the bound that actually ends that.
     */
    @NotNull
    private Duration transferTimeout = Duration.ofMinutes(30);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isRequireTls() {
        return requireTls;
    }

    public void setRequireTls(boolean requireTls) {
        this.requireTls = requireTls;
    }

    public List<String> getPermittedHosts() {
        return permittedHosts;
    }

    public void setPermittedHosts(List<String> permittedHosts) {
        this.permittedHosts = permittedHosts == null ? new ArrayList<>() : permittedHosts;
    }

    public DataSize getMaxContentSize() {
        return maxContentSize;
    }

    public void setMaxContentSize(DataSize maxContentSize) {
        this.maxContentSize = maxContentSize;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getResponseTimeout() {
        return responseTimeout;
    }

    public void setResponseTimeout(Duration responseTimeout) {
        this.responseTimeout = responseTimeout;
    }

    public Duration getTransferTimeout() {
        return transferTimeout;
    }

    public void setTransferTimeout(Duration transferTimeout) {
        this.transferTimeout = transferTimeout;
    }
}
