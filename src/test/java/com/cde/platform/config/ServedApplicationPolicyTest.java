package com.cde.platform.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The policy over a page this image serves, and the configuration that decides
 * whether it serves one at all.
 *
 * <p>No Spring context anywhere here: these are pure functions and a
 * properties object, and the container the integration assertions need does not
 * start in every environment. Composition is where the mistakes hide.
 */
class ServedApplicationPolicyTest {

    private static final String NONCE = "r4nd0mV4lu3";
    private static final String HOST_CDE = "https://cde.customer.example";

    @Nested
    @DisplayName("The document policy")
    class DocumentPolicy {

        @Test
        @DisplayName("lets the application load its own scripts and styles")
        void applicationCanLoadItself() {
            // default-src 'none' is right for JSON and fatal for a page: it
            // refuses the application's own bundle, and the result is a blank
            // frame with nothing in the server log at all.
            String policy = ContentSecurityPolicies.singlePageApp(NONCE);

            assertThat(policy)
                .contains("default-src 'self'")
                .contains("script-src 'self'")
                .doesNotContain("default-src 'none'");
        }

        @Test
        @DisplayName("names the nonce instead of permitting inline styles")
        void stylesAreNoncedNotUnsafe() {
            // §5.4 forbids 'unsafe-inline'. Angular injects component styles at
            // runtime, so something has to give — a nonce accepts exactly the
            // styles this application emits and still refuses an injected one,
            // which 'unsafe-inline' could not tell apart.
            String policy = ContentSecurityPolicies.singlePageApp(NONCE);

            assertThat(policy)
                .contains("style-src 'self' 'nonce-" + NONCE + "'")
                .doesNotContain("unsafe-inline")
                .doesNotContain("unsafe-eval");
        }

        @Test
        @DisplayName("is not framable")
        void theApplicationItselfIsNotFramable() {
            assertThat(ContentSecurityPolicies.singlePageApp(NONCE))
                .contains("frame-ancestors 'none'");
        }
    }

    @Nested
    @DisplayName("The embed document policy")
    class EmbedDocumentPolicy {

        private static final String STORAGE = "https://contoso.sharepoint.example";
        private static final String OTHER_STORAGE = "https://files.customer.example";

        /** One directive's value, so a substring cannot pass for the whole. */
        private static String directive(String policy, String name) {
            return java.util.Arrays.stream(policy.split(";"))
                .map(String::trim)
                .filter(part -> part.startsWith(name + " "))
                .map(part -> part.substring(name.length() + 1))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " absent from: " + policy));
        }

        /** The composition SecurityConfig performs, in one place. */
        private static String embedPolicy(String host, List<String> documentOrigins) {
            return ContentSecurityPolicies.embeddedViewer(
                NONCE,
                ContentSecurityPolicies.frameAncestorsFor(List.of(host)),
                ContentSecurityPolicies.connectSourcesFor(documentOrigins));
        }

        @Test
        @DisplayName("names the configured hosts and never a wildcard")
        void framingIsOpenedOnlyToNamedHosts() {
            String policy = embedPolicy(HOST_CDE, List.of());

            assertThat(policy)
                .contains("frame-ancestors " + HOST_CDE)
                .doesNotContain("frame-ancestors *")
                .doesNotContain("frame-ancestors 'self'");
        }

        @Test
        @DisplayName("permits any https origin when none are named")
        void connectSourcePermitsTheIntegratorsStorage() {
            // The one real widening over the application's own policy, and the
            // reason is structural: the document URL is minted by the
            // integrator on their own storage, which is not knowable when this
            // image is built. connect-src 'self' would frame correctly and then
            // open nothing.
            assertThat(directive(embedPolicy(HOST_CDE, List.of()), "connect-src"))
                .isEqualTo("'self' https:");
        }

        @Test
        @DisplayName("narrows to the named storage origins, dropping the blanket https:")
        void namedDocumentOriginsReplaceTheBlanket() {
            String policy = embedPolicy(HOST_CDE, List.of(STORAGE, "http://localhost:4401"));

            // Replacing rather than adding is the whole point: a deployment
            // that names its integrators' storage should end up with a
            // narrower policy, not the same one with extra entries. Keeping
            // https: alongside would make configuring this a no-op.
            //
            // Asserted as the whole directive rather than a substring. A
            // doesNotContain("'self' https:") reads correctly and is wrong:
            // "'self' https://contoso…" contains it, so the check would pass
            // whatever the code did.
            assertThat(directive(policy, "connect-src"))
                .isEqualTo("'self' " + STORAGE + " http://localhost:4401");
        }

        @Test
        @DisplayName("lists sources space-separated, as the CSP grammar requires")
        void sourcesAreSpaceSeparated() {
            // A comma is not a syntax error the browser reports: it is one
            // source it cannot parse, which it drops while applying the rest.
            assertThat(ContentSecurityPolicies.connectSourcesFor(List.of(STORAGE, OTHER_STORAGE)))
                .isEqualTo("'self' " + STORAGE + " " + OTHER_STORAGE)
                .doesNotContain(",");
        }

        @Test
        @DisplayName("keeps the widening off the application's own pages")
        void theWideningIsScopedToTheEmbed() {
            assertThat(ContentSecurityPolicies.singlePageApp(NONCE))
                .contains("connect-src 'self';")
                .doesNotContain("https:");
        }

        @Test
        @DisplayName("still refuses plain http and object embedding")
        void theWideningIsNotADoorLeftOpen() {
            assertThat(embedPolicy(HOST_CDE, List.of()))
                .doesNotContain("http:;")
                .contains("object-src 'none'")
                .contains("base-uri 'self'");
        }
    }

    @Nested
    @DisplayName("Where the application is served from")
    class ApplicationPath {

        private BrowserApplicationProperties pointedAt(Path directory) {
            var properties = new BrowserApplicationProperties();
            properties.setPath(directory.toString());
            return properties;
        }

        @Test
        @DisplayName("serves nothing when unset, which is what it did before")
        void unsetMeansNoApplication() {
            var properties = new BrowserApplicationProperties();

            assertThat(properties.isConfigured()).isFalse();
            assertThatCode(properties::requireApplicationPresentWhenConfigured)
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("accepts a directory holding an index")
        void acceptsABuiltBundle(@TempDir Path bundle) throws Exception {
            Files.writeString(bundle.resolve("index.html"), "<app-root></app-root>");

            assertThatCode(pointedAt(bundle)::requireApplicationPresentWhenConfigured)
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("refuses a path that is not there, at startup")
        void refusesAMissingDirectory(@TempDir Path parent) {
            Path absent = parent.resolve("never-mounted");

            // Without this the application boots, serves its API perfectly, and
            // 404s every page — which reads as a routing fault and is a
            // mounting one.
            assertThatThrownBy(pointedAt(absent)::requireApplicationPresentWhenConfigured)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a directory")
                .hasMessageContaining(absent.toString());
        }

        @Test
        @DisplayName("refuses the output directory when the bundle is one level below")
        void refusesTheWrongLevelOfTheBuildOutput(@TempDir Path output) throws Exception {
            // The usual mistake: the Angular application builder writes
            // dist/cde-web/browser/index.html, and dist/cde-web is the path
            // that looks right.
            Files.createDirectory(output.resolve("browser"));
            Files.writeString(output.resolve("browser/index.html"), "<app-root></app-root>");

            assertThatThrownBy(pointedAt(output)::requireApplicationPresentWhenConfigured)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no index.html")
                .hasMessageContaining("dist/cde-web/browser");
        }
    }
}
