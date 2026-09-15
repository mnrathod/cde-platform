package com.cde.platform.config;

import java.util.List;

/**
 * Every content-security policy this application sends, and the composition
 * that produces them.
 *
 * <p>Extracted from {@code SecurityConfig} when serving the browser
 * application from this image pushed that class to §3.3's 400-line limit. The
 * extraction earns its own file for a better reason than size: policy
 * composition is a pure function of its inputs, and the integration assertions
 * that exercise it need a database container to raise a Spring context at all.
 * Here it is testable wherever that container is not — which is where a comma
 * in place of a space, or a {@code 'none'} left beside a real origin, would
 * otherwise hide.
 *
 * <p>Four policies, because four kinds of response need different things and
 * a single global policy would have to be the loosest of them.
 */
final class ContentSecurityPolicies {

    private ContentSecurityPolicies() {
    }

    /**
     * The API's own policy: load nothing, from anywhere.
     *
     * <p>This server answers with JSON, so it has no legitimate need to load a
     * script, a stylesheet, a frame or a plugin — {@code 'none'} is both the
     * strictest policy and the accurate one. It matters on the paths that
     * <em>do</em> return markup: an error page, and anything a future
     * misconfiguration causes to be rendered rather than serialised.
     *
     * <p>{@code frame-ancestors} is the modern replacement for
     * X-Frame-Options and is what actually stops the viewer being framed by a
     * site that wants a user's clicks; both are sent, because older browsers
     * read only the latter.
     */
    static String api(String frameAncestors) {
        return "default-src 'none'; frame-ancestors " + frameAncestors
            + "; base-uri 'none'; form-action 'none'";
    }

    /**
     * A narrower relaxation for the API documentation page only.
     *
     * <p>Swagger UI is a real single-page application: it loads its bundle
     * from this origin and applies inline styles as it renders. {@code
     * style-src 'unsafe-inline'} is therefore unavoidable for it to display,
     * and is scoped to this one path rather than granted everywhere.
     *
     * <p>The concession is to <em>styles</em>, not scripts. An inline style
     * cannot execute JavaScript; {@code script-src 'self'} still refuses any
     * injected script, which is the property that matters.
     */
    static String documentation() {
        return "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data:; font-src 'self'; connect-src 'self'; "
            + "frame-ancestors 'none'; object-src 'none'; base-uri 'self'";
    }

    /**
     * The browser application's own document, at {@code /} and its client-side
     * routes.
     *
     * <p>Unlike the documentation page this gets a **nonce** rather than
     * {@code style-src 'unsafe-inline'}, which §5.4 forbids. Angular injects
     * component styles as {@code <style>} elements at runtime, so some
     * provision has to be made; a nonce makes exactly the styles this
     * application emits acceptable and leaves an injected one refused, where
     * {@code 'unsafe-inline'} would accept both and could not tell them apart.
     *
     * <p>Not framable. Only the embed route is, and only when a deployment has
     * named the hosts (ADR 14).
     */
    static String singlePageApp(String nonce) {
        return browserApplication(nonce, "'self'", "'none'");
    }

    /**
     * The embed route's document — the same application, with two differences.
     *
     * <p>{@code frame-ancestors} names the hosts permitted to frame it, and
     * {@code connect-src} permits somewhere other than this origin to fetch a
     * document from. The second is the one worth explaining, because it is a
     * real widening: an embedded viewer fetches the document from a short-lived
     * URL the <em>integrator</em> mints, on their own storage — SharePoint, S3,
     * Azure Blob, a customer's own host. A {@code connect-src 'self'} would
     * refuse every one of them, so the embed would frame correctly and then
     * open nothing.
     *
     * @param connectSources from {@link #connectSourcesFor}
     */
    static String embeddedViewer(String nonce, String frameAncestors, String connectSources) {
        return browserApplication(nonce, connectSources, frameAncestors);
    }

    /**
     * The embed route's {@code connect-src}, from the configured storage
     * origins.
     *
     * <p>Empty yields {@code 'self' https:} — the only default that can work,
     * since the integrator's storage is not knowable at image-build time. It is
     * scoped to the embed document and no further, still refuses plain
     * {@code http:}, and is not {@code *}.
     *
     * <p>Naming origins <strong>replaces</strong> the blanket rather than
     * adding to it, so configuring this narrows the policy — which is the point
     * of it, and why there is no way to say "https: and also these". A
     * deployment needing both keeps the default.
     */
    static String connectSourcesFor(List<String> documentOrigins) {
        return documentOrigins.isEmpty()
            ? "'self' https:"
            : "'self' " + String.join(" ", documentOrigins);
    }

    /**
     * @param nonce           the per-request value Angular is also given, so
     *                        the styles it injects are the only inline styles
     *                        the browser will accept
     * @param connectSources  where the page may fetch from
     * @param frameAncestors  who may frame it
     */
    private static String browserApplication(String nonce, String connectSources,
                                             String frameAncestors) {
        return "default-src 'self'; "
            // The nonce is on scripts as well as styles, because a production
            // build emits one inline script: the few lines that promote the
            // deferred stylesheet from media="print" to media="all" once it
            // has loaded. Refuse it and the main stylesheet never applies —
            // verified in a browser, where the page rendered with only the
            // inlined critical CSS and looked broken rather than unstyled.
            //
            // This is what §5.4 means by a nonce-based policy, and it is not a
            // relaxation towards 'unsafe-inline': an injected script carries no
            // nonce and is still refused. The two differ in exactly the way
            // that matters — one names the scripts this response emitted, the
            // other accepts any script at all.
            + "script-src 'self' 'nonce-" + nonce + "'; "
            + "style-src 'self' 'nonce-" + nonce + "'; "
            // data: and blob: are what a rendered page is made of — pdf.js
            // paints to a canvas and hands back object URLs, and refusing them
            // renders every document blank with nothing in the log but a CSP
            // violation nobody is collecting yet.
            + "img-src 'self' data: blob:; "
            + "font-src 'self'; "
            + "connect-src " + connectSources + "; "
            // pdf.js runs its parser in a worker. The bundled one is same
            // origin; blob: covers the fallback it constructs when that fails.
            + "worker-src 'self' blob:; "
            + "object-src 'none'; "
            + "base-uri 'self'; "
            + "form-action 'self'; "
            + "frame-ancestors " + frameAncestors;
    }

    /**
     * The {@code frame-ancestors} source list for a set of configured hosts.
     *
     * <p>Space-separated, because that is what the CSP grammar says; a
     * comma-separated list is not a syntax error the browser reports, it is one
     * source it cannot parse, which it drops while applying what is left. An
     * empty configuration yields {@code 'none'} rather than an empty directive,
     * because an empty {@code frame-ancestors} is invalid and an invalid
     * directive is ignored — which would leave the route framable by anyone.
     */
    static String frameAncestorsFor(List<String> embeddingHosts) {
        return embeddingHosts.isEmpty() ? "'none'" : String.join(" ", embeddingHosts);
    }
}
