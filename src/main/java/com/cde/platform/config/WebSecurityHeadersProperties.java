package com.cde.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.net.URISyntaxException;
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
     * Origins permitted to frame the embed route, as full scheme-host-port
     * values ({@code https://cde.customer.example}).
     *
     * <p>This is the {@code frame-ancestors} allow-list ADR 14 requires, and it
     * is the <em>authorisation</em> decision about who may embed this viewer.
     * The {@code parentOrigin} the viewer is given in its URL is only
     * addressing — it says where to post messages, not who is permitted to
     * frame. A deployment that relaxed only the second would be framable by
     * anyone who sent the right message.
     *
     * <p><strong>Empty by default, and empty means {@code 'none'}</strong> — an
     * unconfigured deployment refuses framing exactly as it did before this
     * setting existed. Opening the embed is a deliberate act, never a default.
     *
     * <p>Deployment-level rather than per-tenant, and that is a limit worth
     * stating: the embed route carries no credential by design (ADR 14 — the
     * viewer authenticates nobody), so there is no authenticated principal to
     * derive a tenant from. Deriving it from a query parameter or a header
     * instead would let a caller nominate its own allow-list, which is a
     * wildcard with extra steps. Narrowing this per tenant needs a
     * discriminator the request cannot forge; see {@code docs/configuration.md}.
     */
    private List<String> embedParentOrigins = new ArrayList<>();

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

    public List<String> getEmbedParentOrigins() {
        return embedParentOrigins;
    }

    public void setEmbedParentOrigins(List<String> embedParentOrigins) {
        this.embedParentOrigins =
            embedParentOrigins == null ? new ArrayList<>() : embedParentOrigins;
    }

    /** @return true when a deployment has named at least one embedding host. */
    public boolean hasEmbeddingHosts() {
        return !embedParentOrigins.isEmpty();
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
        embedParentOrigins.forEach(WebSecurityHeadersProperties::requireExactOrigin);
    }

    /**
     * Refuse anything that is not one exact, absolute origin.
     *
     * <p>Checked at startup rather than when a browser first parses the header,
     * because a {@code frame-ancestors} directive a browser cannot parse is not
     * a closed door — the browser ignores the malformed source and applies what
     * is left, so a typo silently widens the policy instead of breaking
     * visibly. Everything here is a way that has actually gone wrong somewhere:
     *
     * <ul>
     *   <li>{@code *} and {@code https://*.example.com} — any site may frame
     *       the viewer, which is the whole control gone.
     *   <li>{@code 'self'} and {@code none} — CSP keywords rather than origins,
     *       which read as deliberate and are not what the deployment meant.
     *   <li>{@code null} — matches a sandboxed frame and a {@code data:}
     *       document, so it grants exactly the contexts least worth trusting.
     *   <li>A trailing path or slash ({@code https://a.example/}) — not an
     *       origin. Browsers match the origin and ignore the rest, so it works
     *       by accident and stops working when it is tightened.
     * </ul>
     *
     * @throws IllegalStateException naming the offending value, because a list
     *         of five origins with one typo is not searchable from a generic
     *         message.
     */
    private static void requireExactOrigin(String candidate) {
        String value = candidate == null ? "" : candidate.trim();
        String reason = reasonItIsNotAnOrigin(value);
        if (reason != null) {
            throw new IllegalStateException(
                "cde.web.embed-parent-origins contains " + describe(candidate) + ": " + reason
                + " Name each embedding host as one exact origin "
                + "(https://cde.customer.example), scheme and host and port, nothing else. "
                + "Leave the list empty to refuse framing altogether.");
        }
    }

    private static String reasonItIsNotAnOrigin(String value) {
        if (value.isEmpty()) {
            return "an empty entry.";
        }
        if (value.contains("*")) {
            return "a wildcard, which would let any matching site frame the viewer.";
        }
        if (value.equals("null") || value.startsWith("'")) {
            return "a CSP keyword rather than an origin; 'null' in particular matches "
                + "sandboxed and data: documents.";
        }
        URI parsed;
        try {
            parsed = new URI(value);
        } catch (URISyntaxException malformed) {
            return "not a URI at all.";
        }
        String scheme = parsed.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
            return "not an http or https origin.";
        }
        if (parsed.getHost() == null) {
            return "an origin with no host.";
        }
        // Anything after the authority makes this not an origin. Rebuilding it
        // and comparing is stricter than checking getPath().isEmpty(), because
        // it also catches a query, a fragment, and userinfo.
        String origin = scheme + "://" + parsed.getHost()
            + (parsed.getPort() == -1 ? "" : ":" + parsed.getPort());
        if (!origin.equals(value)) {
            return "more than an origin — it would have been read as " + origin + ".";
        }
        return null;
    }

    private static String describe(String candidate) {
        return candidate == null || candidate.isBlank()
            ? "a blank entry" : "\"" + candidate + "\"";
    }
}
