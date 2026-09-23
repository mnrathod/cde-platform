package com.cde.platform.config;

import com.cde.platform.security.ContentSecurityPolicyNonce;
import com.cde.platform.web.BrowserApplicationRoutes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer.HstsConfig;
import org.springframework.security.web.header.writers.ContentSecurityPolicyHeaderWriter;
import org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy;
import org.springframework.security.web.header.writers.CrossOriginResourcePolicyHeaderWriter.CrossOriginResourcePolicy;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter;
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter.XFrameOptionsMode;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.pathPattern;

/**
 * Which response headers each route gets, and what they say.
 *
 * <p>Split out of {@link SecurityConfig}, which had grown past the §3.3 limit
 * carrying two jobs: deciding who may reach what, and deciding what a browser
 * is told once a response is on its way back. This is the second. It is the
 * larger of the two because the content policy is not one string — the API,
 * the documentation pages and the application's own pages each need a
 * different one, and the application's is composed per request because it
 * names a nonce.
 */
class ResponseHeaderHardening {

    private final WebSecurityHeadersProperties webProperties;

    ResponseHeaderHardening(WebSecurityHeadersProperties webProperties) {
        this.webProperties = webProperties;
    }

    /**
     * Paths whose responses are markup rendered by a browser.
     *
     * <p>The policies themselves live in {@link ContentSecurityPolicies}; what
     * is here is which one each request gets.
     */
    private static final RequestMatcher DOCUMENTATION_PAGES =
        new OrRequestMatcher(pathPattern("/api/docs/**"),
                             pathPattern("/api/docs"),
                             pathPattern("/api/swagger-ui/**"),
                             pathPattern("/swagger-ui/**"));

    /**
     * The one route a host application is permitted to frame (ADR 14).
     *
     * <p>Deliberately narrow. {@code frame-ancestors} is relaxed here and
     * nowhere else, so widening it is a change to this constant rather than a
     * change to a policy string that happens to apply everywhere.
     */
    private static final RequestMatcher EMBED_ROUTE =
        new OrRequestMatcher(pathPattern(BrowserApplicationRoutes.EMBED),
                             pathPattern(BrowserApplicationRoutes.EMBED + "/**"));

    /**
     * Every route the browser application owns, including the embed one.
     *
     * <p>These serve markup rather than JSON, so {@code default-src 'none'}
     * would refuse the application's own scripts and styles and render a blank
     * page. The list is {@link BrowserApplicationRoutes#ALL}; the controller
     * repeats it literally and a test asserts the two agree.
     */
    private static final RequestMatcher APP_DOCUMENT_ROUTES = new OrRequestMatcher(
        java.util.Arrays.stream(BrowserApplicationRoutes.ALL)
            .map(route -> (RequestMatcher) pathPattern(route))
            .toList());

    /** Every path that keeps the default, unframable, load-nothing policy. */
    private static final RequestMatcher STANDARD_ROUTES = new NegatedRequestMatcher(
        new OrRequestMatcher(DOCUMENTATION_PAGES, APP_DOCUMENT_ROUTES));

    /**
     * The response headers a browser needs in order to apply the protections
     * it already implements.
     *
     * <p>Spring Security supplies {@code nosniff}, {@code Cache-Control:
     * no-store} and X-Frame-Options by default; the rest were simply absent,
     * so a browser talking to this API enforced no transport policy, no
     * content policy, no referrer policy and no feature policy.
     */
    void hardenResponseHeaders(HeadersConfigurer<HttpSecurity> headers) {
        headers
            // X-Frame-Options has no allow-list form — ALLOW-FROM was removed
            // from every browser — so on the embed route it can only say
            // SAMEORIGIN, and a browser honouring it would refuse the frame
            // whatever frame-ancestors says. Spring's own frameOptions() is
            // therefore off, and the header is written for every route except
            // the embed one, where CSP is the sole and sufficient control.
            .frameOptions(HeadersConfigurer.FrameOptionsConfig::disable)
            .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                new NegatedRequestMatcher(EMBED_ROUTE),
                new XFrameOptionsHeaderWriter(XFrameOptionsMode.SAMEORIGIN)))
            .referrerPolicy(referrer -> referrer.policy(
                ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
            // Isolates this origin's browsing context, so a window it opens —
            // or that opens it — cannot reach into it.
            .crossOriginOpenerPolicy(policy -> policy.policy(
                CrossOriginOpenerPolicy.SAME_ORIGIN))
            .crossOriginResourcePolicy(policy -> policy.policy(
                CrossOriginResourcePolicy.SAME_ORIGIN))
            // Two policies, chosen by path: the strict one everywhere, and the
            // documentation relaxation only on the documentation paths. A
            // single global policy would have to be the looser of the two.
            .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                DOCUMENTATION_PAGES,
                new ContentSecurityPolicyHeaderWriter(documentationPolicy())))
            // Not a ContentSecurityPolicyHeaderWriter: this policy carries a
            // nonce, so it is composed per request rather than once at startup.
            .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                APP_DOCUMENT_ROUTES, this::writeDocumentPolicy))
            .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                STANDARD_ROUTES,
                new ContentSecurityPolicyHeaderWriter(apiPolicy())));

        // `permissionsPolicyHeader`, not `permissionsPolicy`: the latter is
        // deprecated for removal in Spring Security 7 (§0.2), and returns its
        // own config object rather than the HeadersConfigurer, which is why
        // this call used to sit outside the chain above. The replacement takes
        // the same customiser and returns the configurer, so it could be
        // chained — it is left standing alone because the header it sets has
        // nothing to do with the content-security policies above it.
        headers.permissionsPolicyHeader(permissions -> permissions.policy(
            "accelerometer=(), autoplay=(), camera=(), display-capture=(), "
            + "encrypted-media=(), fullscreen=(self), geolocation=(), gyroscope=(), "
            + "magnetometer=(), microphone=(), midi=(), payment=(), "
            + "picture-in-picture=(), usb=(), xr-spatial-tracking=()"));

        if (webProperties.isHstsEnabled()) {
            headers.httpStrictTransportSecurity(hsts -> hsts
                .maxAgeInSeconds(webProperties.getHstsMaxAgeSeconds())
                .includeSubDomains(true)
                .preload(true));
        } else {
            headers.httpStrictTransportSecurity(HstsConfig::disable);
        }
    }

    private String apiPolicy() {
        return withReportUri(ContentSecurityPolicies.api("'none'"));
    }

    private String documentationPolicy() {
        return withReportUri(ContentSecurityPolicies.documentation());
    }

    /**
     * The policy for a page this application serves, composed per request.
     *
     * <p>Per request because it names a nonce, and a nonce reused across
     * requests is not a nonce. {@link ContentSecurityPolicyNonce} puts the
     * value where both this and the controller rendering the document can read
     * it; if they ever disagreed the page would render unstyled, which is a
     * visible failure rather than a quiet weakening.
     *
     * <p>The embed route is the only one whose framing is ever opened, and only
     * when a deployment has named the hosts. Everywhere else — including the
     * application's own pages — stays {@code 'none'}.
     */
    private void writeDocumentPolicy(HttpServletRequest request, HttpServletResponse response) {
        if (response.containsHeader("Content-Security-Policy")) {
            return;
        }
        String nonce = ContentSecurityPolicyNonce.currentNonce(request);
        boolean framable = EMBED_ROUTE.matches(request);
        String policy = framable
            ? ContentSecurityPolicies.embeddedViewer(
                nonce,
                ContentSecurityPolicies.frameAncestorsFor(
                    webProperties.getEmbedParentOrigins()),
                ContentSecurityPolicies.connectSourcesFor(
                    webProperties.getEmbedDocumentOrigins()))
            : ContentSecurityPolicies.singlePageApp(nonce);
        response.setHeader("Content-Security-Policy", withReportUri(policy));
    }

    private String withReportUri(String policy) {
        String reportUri = webProperties.getCspReportUri();
        return reportUri.isBlank() ? policy : policy + "; report-uri " + reportUri;
    }
}
