package com.cde.platform.web;

import com.cde.platform.config.BrowserApplicationProperties;
import com.cde.platform.security.ContentSecurityPolicyNonce;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Putting the nonce in the document.
 *
 * <p>The header and the markup have to name the same value or the browser
 * refuses every style the page carries. This is the markup half; the header
 * half is in {@code ServedApplicationPolicyTest}. Both run without a Spring
 * context — the controller takes its properties in a constructor and its
 * request as an argument, which is what makes that possible.
 */
class BrowserApplicationControllerTest {

    /**
     * Shaped like a real production build rather than a minimal page.
     *
     * <p>Three placeholders, in the three places an Angular production build
     * actually puts them: the inlined critical CSS, the script that promotes
     * the deferred stylesheet, and the root element. A fixture with only the
     * last would pass against an implementation that reached only the last,
     * which is the bug this file was rewritten to catch.
     */
    private static final String INDEX = """
        <!doctype html>
        <html lang="en"><head><title>CDE Platform</title>
        <style nonce="CDE_CSP_NONCE">:root{--bg:#fff}</style>
        <script nonce="CDE_CSP_NONCE">/* promotes the deferred stylesheet */</script>
        <link rel="stylesheet" href="styles-AAAA.css" media="print" ngcspmedia="all"></head>
        <body><app-root ngcspnonce="CDE_CSP_NONCE"></app-root>
        <script src="main-AAAA.js" type="module" nonce="CDE_CSP_NONCE"></script></body></html>
        """;

    private static BrowserApplicationProperties servingFrom(Path directory) {
        var properties = new BrowserApplicationProperties();
        properties.setPath(directory.toString());
        return properties;
    }

    private static BrowserApplicationController servingIndex(Path bundle, String index)
            throws Exception {
        Files.writeString(bundle.resolve("index.html"), index);
        return new BrowserApplicationController(servingFrom(bundle));
    }

    private static MockHttpServletRequest requestWithNonce(String nonce) {
        var request = new MockHttpServletRequest("GET", "/embed");
        request.setAttribute(ContentSecurityPolicyNonce.REQUEST_ATTRIBUTE, nonce);
        return request;
    }

    @Test
    @DisplayName("replaces every placeholder, not just the one on the root element")
    void theNonceReachesEveryPlaceholder(@TempDir Path bundle) throws Exception {
        var controller = servingIndex(bundle, INDEX);

        String body = controller.document(requestWithNonce("abc123")).getBody();

        // All four: inlined critical CSS, the stylesheet-promoting script, the
        // root element, and the module script. Leaving the inlined CSS out is
        // the specific mistake that renders a correctly-served page unstyled.
        assertThat(body).doesNotContain("CDE_CSP_NONCE");
        assertThat(body.split("abc123", -1).length - 1).isEqualTo(4);
        assertThat(body).contains("<style nonce=\"abc123\">");
    }

    @Test
    @DisplayName("gives a different document to the next request")
    void everyRequestGetsItsOwnNonce(@TempDir Path bundle) throws Exception {
        var controller = servingIndex(bundle, INDEX);

        String first = controller.document(requestWithNonce("first")).getBody();
        String second = controller.document(requestWithNonce("second")).getBody();

        // A cached template that kept the first nonce would serve every later
        // visitor a document whose value the policy no longer names.
        assertThat(first).contains("\"first\"").doesNotContain("\"second\"");
        assertThat(second).contains("\"second\"").doesNotContain("\"first\"");
    }

    @Test
    @DisplayName("forbids caching the document, because the nonce is in it")
    void theDocumentIsNeverStored(@TempDir Path bundle) throws Exception {
        var controller = servingIndex(bundle, INDEX);

        ResponseEntity<String> response = controller.document(requestWithNonce("abc123"));

        // A proxy holding this would hand a later visitor a document whose
        // nonce the policy on their response does not name, and the application
        // would render unstyled for as long as the cache held it.
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
            .contains("no-store");
    }

    @Test
    @DisplayName("refuses to start when the build left nowhere to put the nonce")
    void anIndexWithoutThePlaceholderFailsAtStartup(@TempDir Path bundle) throws Exception {
        // What an Angular build emits when src/index.html has no ngCspNonce:
        // valid markup, inlined CSS with no nonce on it. It serves perfectly
        // and renders unstyled, which reads as a broken stylesheet rather than
        // a security header and is found in a browser days later.
        assertThatThrownBy(() -> servingIndex(bundle,
            "<html><head><style>:root{--bg:#fff}</style></head>"
            + "<body><app-root></app-root></body></html>"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ngCspNonce")
            .hasMessageContaining("renders unstyled");
    }

    @Test
    @DisplayName("serves nothing, and reads nothing, when no application is configured")
    void unconfiguredServesNoDocument() {
        var controller = new BrowserApplicationController(new BrowserApplicationProperties());

        ResponseEntity<String> response = controller.document(requestWithNonce("abc123"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("renders an empty nonce rather than the word null")
    void aMissingNonceIsEmptyNotNull(@TempDir Path bundle) throws Exception {
        var controller = servingIndex(bundle, INDEX);

        // "nonce-null" is syntactically valid and matches nothing, so the page
        // would render unstyled with a policy that looks correct. An empty
        // value is at least obviously wrong.
        String body = controller.document(new MockHttpServletRequest("GET", "/embed")).getBody();

        assertThat(body).contains("nonce=\"\"").doesNotContain("null");
    }
}
