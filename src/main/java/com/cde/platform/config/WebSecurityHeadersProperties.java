package com.cde.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

/**
 * Browser-facing hardening: which origins may call this API, and what the
 * response headers assert.
 *
 * <p>Both used to be absent. Cross-origin was configured as {@code
 * allowedOrigins("*")} with every header reflected, which means any page on
 * the internet could call this API from a visitor's browser; and no response
 * carried HSTS, a content security policy, {@code nosniff}, a referrer policy
 * or a permissions policy, so a browser applied none of the protections those
 * headers exist to switch on.
 *
 * <p>The default here is <em>closed</em>: no cross-origin caller is allowed
 * until a deployment names one. A same-origin deployment — the Angular build
 * served by the same web tier that proxies the API, which is how this is meant
 * to run — needs no entry at all and is unaffected.
 */
@ConfigurationProperties(prefix = "cde.web")
@Validated
public class WebSecurityHeadersProperties {

    /**
     * Origins permitted to make cross-origin calls, as full scheme-host-port
     * values ({@code https://app.example.com}).
     *
     * <p>Empty by default, and empty means no CORS configuration is registered
     * at all rather than one that allows everything. A wildcard is rejected:
     * with credentials in play it is meaningless to a browser anyway, and
     * without them it still lets any site read every unauthenticated response.
     */
    private List<String> allowedOrigins = new ArrayList<>();

    /**
     * Whether to send HSTS. On by default. It is inert over plain HTTP, so
     * leaving it on costs a local deployment nothing, and switching it off in
     * the one environment that terminates TLS is the mistake worth preventing.
     */
    private boolean hstsEnabled = true;

    /** One year, which is the minimum most preload lists accept. */
    private long hstsMaxAgeSeconds = 31_536_000L;

    /**
     * Where the browser posts content-security-policy violations. Blank means
     * no reporting directive is added — a policy that reports nowhere still
     * blocks, it just tells nobody it did.
     */
    private String cspReportUri = "";

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins == null ? new ArrayList<>() : allowedOrigins;
    }

    public boolean isHstsEnabled() {
        return hstsEnabled;
    }

    public void setHstsEnabled(boolean hstsEnabled) {
        this.hstsEnabled = hstsEnabled;
    }

    public long getHstsMaxAgeSeconds() {
        return hstsMaxAgeSeconds;
    }

    public void setHstsMaxAgeSeconds(long hstsMaxAgeSeconds) {
        this.hstsMaxAgeSeconds = hstsMaxAgeSeconds;
    }

    public String getCspReportUri() {
        return cspReportUri;
    }

    public void setCspReportUri(String cspReportUri) {
        this.cspReportUri = cspReportUri == null ? "" : cspReportUri;
    }

    /**
     * @return true when a deployment has named at least one cross-origin
     *         caller, so CORS should be registered at all.
     */
    public boolean hasCrossOriginCallers() {
        return !allowedOrigins.isEmpty();
    }

    /**
     * @throws IllegalStateException when a wildcard was configured. Reported at
     *         startup rather than at the first cross-origin request, because a
     *         wildcard that is never exercised in testing is a wildcard that
     *         reaches production.
     */
    public void rejectWildcardOrigins() {
        if (allowedOrigins.stream().anyMatch(origin -> origin.contains("*"))) {
            throw new IllegalStateException("""
                cde.web.allowed-origins contains a wildcard. Name each origin \
                in full (https://app.example.com). A wildcard is ignored by \
                browsers for credentialed requests and, for the rest, lets any \
                site on the internet read this API's responses.""");
        }
    }
}
