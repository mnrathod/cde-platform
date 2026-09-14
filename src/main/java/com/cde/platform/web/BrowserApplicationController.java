package com.cde.platform.web;

import com.cde.platform.config.BrowserApplicationProperties;
import com.cde.platform.security.ContentSecurityPolicyNonce;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Serves the browser application's entry document, with a nonce in it.
 *
 * <p>Only the document. Its scripts, styles and assets are static files and
 * are served as such by {@code BrowserApplicationResourceConfig}; this class
 * exists because the document is the one response that cannot be static — it
 * carries a value that must match the {@code Content-Security-Policy} header
 * sent alongside it, and that value is different on every request.
 *
 * <p>The routes are enumerated rather than caught with a wildcard. A catch-all
 * would also answer paths the API is supposed to refuse, turning a 404 problem
 * document into an HTML page and making a typo'd endpoint look like it exists.
 * The cost is that a new client-side route has to be added here too, which is
 * the trade this makes deliberately.
 *
 * @see com.cde.platform.config.ContentSecurityPolicies
 */
@Controller
// Absent from the OpenAPI specification on purpose: §3.5 governs the API, and
// this serves a page rather than a resource. Listing it would put an operation
// in the customer-facing SDK that returns markup.
@Hidden
public class BrowserApplicationController {

    /**
     * The literal the build leaves wherever a nonce has to go.
     *
     * <p>Substitution rather than injection, and the difference is load-bearing.
     * Writing {@code ngCspNonce} onto {@code <app-root>} here would cover the
     * styles Angular injects at runtime and nothing else — but a production
     * build's {@code index.html} also carries a block of inlined critical CSS
     * and a small script that promotes the deferred stylesheet, and both need
     * the same nonce. The build emits those with the placeholder already on
     * them, <em>because</em> the source {@code index.html} declares
     * {@code ngCspNonce="CDE_CSP_NONCE"}; replacing the placeholder therefore
     * reaches every one of them and injecting an attribute would reach one.
     *
     * <p>The failure that avoids is quiet: the page loads, the application
     * boots, and it renders with no styling at all.
     */
    private static final String NONCE_PLACEHOLDER = "CDE_CSP_NONCE";

    private final String documentTemplate;

    public BrowserApplicationController(BrowserApplicationProperties properties) {
        this.documentTemplate = readAndVerify(properties);
    }

    /**
     * Read the document once and refuse to start if a nonce cannot be put in it.
     *
     * <p>Read once because it is immutable for the life of the container, and
     * verified once because the failure is otherwise invisible: an index built
     * without the placeholder serves perfectly, and the application renders
     * with every style refused. That looks like a broken stylesheet, not a
     * security header, and it is the kind of thing found in a browser three
     * days later.
     */
    private static String readAndVerify(BrowserApplicationProperties properties) {
        if (!properties.isConfigured()) {
            return "";
        }
        String document;
        try {
            document = Files.readString(properties.indexDocument(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(
                "cde.web.app.path names " + properties.indexDocument()
                + " but it could not be read.", unreadable);
        }
        if (!document.contains(NONCE_PLACEHOLDER)) {
            throw new IllegalStateException(
                "The application's index.html contains no \"" + NONCE_PLACEHOLDER + "\" "
                + "placeholder, so this server has nowhere to put the content-security-policy "
                + "nonce. It is put there by the build, from ngCspNonce=\"" + NONCE_PLACEHOLDER
                + "\" on <app-root> in the frontend's src/index.html — an index built without "
                + "it serves perfectly and then renders unstyled, because every style the "
                + "page carries is refused by the browser.");
        }
        return document;
    }

    @GetMapping({
        "/", "/login", "/projects",
        "/embed",
        "/viewer/{id}", "/viewer3d/{id}",
        "/compare", "/visual-compare",
    })
    public ResponseEntity<String> document(HttpServletRequest request) {
        if (documentTemplate.isEmpty()) {
            // Nothing configured to serve. A 404 is the truth, and it keeps
            // this image's behaviour identical to before the setting existed.
            return ResponseEntity.notFound().build();
        }
        String nonce = ContentSecurityPolicyNonce.currentNonce(request);
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_HTML)
            // Load-bearing rather than tidy: this document embeds a nonce that
            // is valid for one request. A cached copy would be served with a
            // later response's policy, the two would disagree, and the
            // application would render unstyled for as long as the cache held.
            .cacheControl(CacheControl.noStore())
            .body(documentTemplate.replace(NONCE_PLACEHOLDER, nonce));
    }
}
