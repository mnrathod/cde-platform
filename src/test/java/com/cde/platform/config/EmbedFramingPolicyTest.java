package com.cde.platform.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

/**
 * Who is permitted to put this viewer in an iframe.
 *
 * <p>ADR 14 decided the embed and then named the consequence: {@code
 * frame-ancestors 'none'} had to become an allow-list on the embed route and
 * stay {@code 'none'} on every other, and the test asserting {@code 'none'}
 * everywhere had to become one that asserts the exception is exactly one route
 * and is never a wildcard. This is that test.
 *
 * <p>Two nested contexts because the interesting property is the difference
 * between them: the same code must refuse framing when nothing is configured
 * and permit exactly the named origins when something is. A single context
 * could only ever demonstrate one of those.
 *
 * <p>These assertions govern responses <em>this application</em> serves. Where
 * a deployment puts the Angular build behind a separate web tier, that tier
 * serves the embed document and must carry the same header — the setting is
 * documented in {@code docs/configuration.md} so both read one source of
 * truth, and a deployment that configures only one of the two will find the
 * frame refused with nothing in this application's logs to explain it.
 */
class EmbedFramingPolicyTest {

    private static final String HOST_CDE = "https://cde.customer.example";
    private static final String HOST_STAGING = "https://staging.cde.customer.example";

    /** Paths that must never become framable, whatever the embed route does. */
    private static final List<String> ROUTES_THAT_STAY_CLOSED =
        List.of("/api/openapi.yaml", "/api/projects", "/api/auth/login", "/api/docs");

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @DisplayName("With no embedding host configured")
    class Unconfigured {

        @Autowired
        private MockMvc mockMvc;

        @Test
        @DisplayName("the embed route refuses framing exactly as every other route does")
        void embedRouteIsClosedByDefault() throws Exception {
            // The property that makes this change safe to merge before anyone
            // has decided which customers may embed: adding the route does not,
            // on its own, make anything framable. Opening it is a deliberate
            // act of configuration, and until it happens this route is as shut
            // as it was before it existed.
            mockMvc.perform(get("/embed"))
                .andExpect(header().string("Content-Security-Policy",
                    containsString("frame-ancestors 'none'")));
        }

        @Test
        @DisplayName("every other route refuses framing too")
        void everythingElseIsClosed() throws Exception {
            for (String route : ROUTES_THAT_STAY_CLOSED) {
                mockMvc.perform(get(route))
                    .andExpect(header().string("Content-Security-Policy",
                        containsString("frame-ancestors 'none'")));
            }
        }
    }

    @Nested
    @SpringBootTest(properties =
        "cde.web.embed-parent-origins=" + HOST_CDE + "," + HOST_STAGING)
    @AutoConfigureMockMvc
    @DisplayName("With two embedding hosts configured")
    class Configured {

        @Autowired
        private MockMvc mockMvc;

        @Test
        @DisplayName("the embed route names those hosts and nothing else")
        void embedRouteNamesTheConfiguredHosts() throws Exception {
            mockMvc.perform(get("/embed"))
                .andExpect(header().string("Content-Security-Policy",
                    containsString("frame-ancestors " + HOST_CDE + " " + HOST_STAGING)));
        }

        @Test
        @DisplayName("the embed route is never a wildcard")
        void embedRouteIsNeverAWildcard() throws Exception {
            // ADR 14 asks for this assertion by name. A wildcard here would
            // undo the isolation the whole embed decision rests on, and it is
            // the single edit most likely to be made in a hurry by someone
            // debugging a refused frame.
            mockMvc.perform(get("/embed"))
                .andExpect(header().string("Content-Security-Policy",
                    not(containsString("frame-ancestors *"))))
                .andExpect(header().string("Content-Security-Policy",
                    not(containsString("frame-ancestors 'self'"))));
        }

        @Test
        @DisplayName("opening the embed route opens nothing else")
        void relaxationDoesNotLeakToOtherRoutes() throws Exception {
            // The failure this exists to catch is a policy relaxed globally
            // because relaxing it globally was easier. Every one of these
            // would still serve its own response perfectly well while framable
            // by the customer's site, and nothing else in the suite would
            // notice.
            for (String route : ROUTES_THAT_STAY_CLOSED) {
                mockMvc.perform(get(route))
                    .andExpect(header().string("Content-Security-Policy",
                        containsString("frame-ancestors 'none'")))
                    .andExpect(header().string("Content-Security-Policy",
                        not(containsString(HOST_CDE))));
            }
        }

        @Test
        @DisplayName("X-Frame-Options is withheld on the embed route and sent everywhere else")
        void xFrameOptionsIsWithheldOnlyWhereItWouldContradictTheAllowList()
                throws Exception {
            // X-Frame-Options has no allow-list form — ALLOW-FROM is gone from
            // every browser — so on the embed route it could only say
            // SAMEORIGIN, and a browser honouring it would refuse the frame
            // however the CSP reads. Withheld there, kept everywhere else,
            // where it is a free second layer for older browsers.
            mockMvc.perform(get("/embed"))
                .andExpect(header().doesNotExist("X-Frame-Options"));

            mockMvc.perform(get("/api/openapi.yaml"))
                .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"));
        }
    }

    /**
     * The policy string itself, with no Spring context.
     *
     * <p>Every assertion above needs a database container to raise a context.
     * This composition does not, and it is where the mistakes that matter
     * hide — a comma where the grammar wants a space, or an empty directive
     * that a browser discards while applying the rest of the policy. Kept
     * runnable wherever the container is not.
     */
    @Nested
    @DisplayName("The policy string")
    class PolicyComposition {

        @Test
        @DisplayName("says 'none' when no host is configured")
        void closedByDefault() {
            assertThat(ContentSecurityPolicies.frameAncestorsFor(List.of())).isEqualTo("'none'");
        }

        @Test
        @DisplayName("separates hosts with spaces, as the CSP grammar requires")
        void hostsAreSpaceSeparated() {
            // A comma-separated list is not an error a browser reports. It is
            // one source the browser cannot parse, which it drops while
            // applying what is left — so the mistake shows up as a frame that
            // works for the first customer and not the second.
            assertThat(ContentSecurityPolicies.frameAncestorsFor(List.of(HOST_CDE, HOST_STAGING)))
                .isEqualTo(HOST_CDE + " " + HOST_STAGING)
                .doesNotContain(",");
        }

        @Test
        @DisplayName("never leaves 'none' beside a real origin")
        void noneIsNotMixedWithOrigins() {
            // 'none' alongside any other source makes the whole directive
            // invalid, and an invalid directive is ignored — which leaves the
            // route framable by anyone. The opposite of what it looks like.
            assertThat(ContentSecurityPolicies.frameAncestorsFor(List.of(HOST_CDE)))
                .doesNotContain("'none'");
        }

        @Test
        @DisplayName("relaxes framing and nothing else")
        void onlyFramingChanges() {
            String strict = ContentSecurityPolicies.api("'none'");
            String open = ContentSecurityPolicies.api(HOST_CDE);

            // The rest of the policy is what stops an injected script running
            // in the frame. Opening the frame must not open that too.
            for (String directive : List.of("default-src 'none'", "base-uri 'none'",
                                            "form-action 'none'")) {
                assertThat(strict).contains(directive);
                assertThat(open).contains(directive);
            }
            assertThat(open).isEqualTo(strict.replace("frame-ancestors 'none'",
                                                      "frame-ancestors " + HOST_CDE));
        }
    }

    /**
     * Startup validation, with no Spring context — these are the values that
     * look like an allow-list and are not one.
     */
    @Nested
    @DisplayName("Configuration validation")
    class Validation {

        private WebSecurityHeadersProperties withEmbedOrigins(String... origins) {
            var properties = new WebSecurityHeadersProperties();
            properties.setEmbedParentOrigins(List.of(origins));
            return properties;
        }

        @Test
        @DisplayName("accepts exact origins, with and without a port")
        void acceptsExactOrigins() {
            assertThatCode(() -> withEmbedOrigins(
                HOST_CDE, "http://localhost:4401", "https://a.example:8443")
                .requireValidOrigins()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("refuses a wildcard, however it is spelled, and says it is a wildcard")
        void refusesWildcards() {
            // The message is asserted, not just the exception. Every wildcard
            // form below is also caught by the later host check — java.net.URI
            // reports no host for "https://*.example" — so an assertion that
            // only demanded "something threw" would pass with the wildcard
            // check deleted. It would then tell a deployer their value is "an
            // origin with no host", which is true and useless: the thing they
            // need told is that a wildcard would let any matching site frame
            // the viewer.
            for (String wildcard : List.of("*", "https://*.customer.example", "*.example")) {
                assertThatThrownBy(() -> withEmbedOrigins(wildcard).requireValidOrigins())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(wildcard)
                    .hasMessageContaining("a wildcard");
            }
        }

        @Test
        @DisplayName("refuses the null origin, which is the one worth refusing most")
        void refusesNullOrigin() {
            // "null" is what a sandboxed iframe and a data: document report as
            // their origin, so permitting it grants exactly the contexts least
            // worth trusting — and it reads, in a config file, like a way of
            // switching the setting off.
            assertThatThrownBy(() -> withEmbedOrigins("null").requireValidOrigins())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sandboxed");
        }

        @Test
        @DisplayName("refuses a CSP keyword offered as an origin")
        void refusesKeywords() {
            assertThatThrownBy(() -> withEmbedOrigins("'self'").requireValidOrigins())
                .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("refuses anything carrying more than an origin, and says what it read")
        void refusesValuesThatAreMoreThanAnOrigin() {
            // A trailing slash or a path works by accident today — browsers
            // match the origin and ignore the rest — so it survives until
            // someone tightens the parser, and then a customer's frame breaks
            // for a reason nobody changed.
            assertThatThrownBy(() ->
                withEmbedOrigins("https://cde.customer.example/embed").requireValidOrigins())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(HOST_CDE);
        }

        @Test
        @DisplayName("refuses a scheme a browser will not match")
        void refusesNonHttpSchemes() {
            assertThatThrownBy(() ->
                withEmbedOrigins("ftp://cde.customer.example").requireValidOrigins())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("http or https");
        }

        @Test
        @DisplayName("an empty list is valid and means framing stays refused")
        void emptyListIsTheClosedDefault() {
            var properties = new WebSecurityHeadersProperties();
            assertThatCode(properties::requireValidOrigins).doesNotThrowAnyException();
            assertThat(properties.hasEmbeddingHosts()).isFalse();
        }

        @Test
        @DisplayName("holds the document origins to the same rules, and names that setting")
        void documentOriginsAreValidatedToo() {
            // The same validator, reached through a second list. The setting
            // name is asserted because the whole value of these messages is
            // that a deployer can find the line they typed — a wildcard in
            // embed-document-origins reported against embed-parent-origins
            // sends them to the wrong file.
            var properties = new WebSecurityHeadersProperties();
            properties.setEmbedDocumentOrigins(List.of("https://*.sharepoint.example"));

            assertThatThrownBy(properties::requireValidOrigins)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cde.web.embed-document-origins")
                .hasMessageContaining("a wildcard")
                .hasMessageContaining("permit any https origin");
        }

        @Test
        @DisplayName("accepts a plain-http document origin, which is what a local demo needs")
        void documentOriginsMayBePlainHttp() {
            // Deliberate: the demo host ships in the frontend repository and
            // serves its samples over http on localhost. Refusing http here
            // would leave the demo unable to open a document against a
            // stock-served viewer, which is the one thing it exists to show.
            var properties = new WebSecurityHeadersProperties();
            properties.setEmbedDocumentOrigins(List.of("http://localhost:4401"));

            assertThatCode(properties::requireValidOrigins).doesNotThrowAnyException();
        }
    }
}
