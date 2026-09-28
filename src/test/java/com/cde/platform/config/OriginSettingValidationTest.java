package com.cde.platform.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What this deployment will accept as an origin, and what it says when it
 * will not.
 *
 * <p>These settings decide who may read this API's responses and who may put
 * the viewer in a frame, so a value that looks like an origin and is not one
 * fails open rather than closed: {@code https://app.example.com/} with a
 * trailing slash matches nothing a browser sends, and the effect is a control
 * that appears configured and is doing nothing. The whole point of validating
 * at startup is that this is found by the deployment failing to boot rather
 * than by somebody eventually noticing the header is absent.
 *
 * <p>Each rejection carries its own reason, and the reasons are asserted
 * individually because they are the entire remedy. An operator reads a startup
 * failure once, at three in the morning, with no debugger: "not an http or
 * https origin" and "more than an origin — it would have been read as
 * https://app.example.com" send them to different places, and a shared generic
 * message would send them to neither.
 */
@DisplayName("what counts as an origin in this deployment's settings")
class OriginSettingValidationTest {

    private WebSecurityHeadersProperties withEmbedParents(String... origins) {
        var properties = new WebSecurityHeadersProperties();
        properties.setEmbedParentOrigins(List.of(origins));
        return properties;
    }

    private WebSecurityHeadersProperties withAllowedOrigins(String... origins) {
        var properties = new WebSecurityHeadersProperties();
        properties.setAllowedOrigins(List.of(origins));
        return properties;
    }

    private String refusalFor(String origin) {
        try {
            withEmbedParents(origin).requireValidOrigins();
        } catch (IllegalStateException refused) {
            return refused.getMessage();
        }
        throw new AssertionError("\"" + origin + "\" was accepted as an origin");
    }

    @Nested
    @DisplayName("settings that are accepted")
    class Accepted {

        @Test
        @DisplayName("no origins at all is a valid configuration")
        void emptyIsValid() {
            // The default, and the safe one: no cross-origin callers and no
            // framing. A deployment that names nothing should start.
            assertThatCode(() -> new WebSecurityHeadersProperties().requireValidOrigins())
                .doesNotThrowAnyException();
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "https://app.example.com",
            "http://localhost:4200",
            "https://cde.customer.example:8443",
        })
        @DisplayName("an exact origin is accepted")
        void exactOriginsAreAccepted(String origin) {
            assertThatCode(() -> withEmbedParents(origin).requireValidOrigins())
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("surrounding whitespace is forgiven rather than fatal")
        void whitespaceIsTrimmed() {
            // A list read from YAML or an environment variable picks these up
            // routinely, and refusing to boot over one is not useful.
            assertThatCode(() -> withEmbedParents("  https://app.example.com  ")
                .requireValidOrigins()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("several origins are each accepted")
        void severalOriginsAreAccepted() {
            assertThatCode(() -> withEmbedParents(
                "https://one.example.com", "https://two.example.com")
                .requireValidOrigins()).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("settings that stop the deployment starting")
    class Rejected {

        @Test
        @DisplayName("a wildcard in the API's allowed origins is refused")
        void wildcardCallerIsRefused() {
            // This is the one that matters most: it is ignored by browsers for
            // credentialed requests, and for everything else it lets any site
            // on the internet read this API's responses.
            assertThatThrownBy(() -> withAllowedOrigins("*").requireValidOrigins())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wildcard")
                .hasMessageContaining("cde.web.allowed-origins");
        }

        @Test
        @DisplayName("a wildcard inside an otherwise-valid origin is refused too")
        void partialWildcardIsRefused() {
            assertThatThrownBy(() -> withAllowedOrigins("https://*.example.com")
                .requireValidOrigins())
                .hasMessageContaining("wildcard");
        }

        @Test
        @DisplayName("a wildcard embedding host is refused, and says why")
        void wildcardEmbedderIsRefused() {
            assertThat(refusalFor("https://*.example.com"))
                .contains("wildcard")
                .contains("nobody has vetted");
        }

        @Test
        @DisplayName("an empty entry is refused rather than silently skipped")
        void emptyEntryIsRefused() {
            // A trailing comma in a list produces one of these, and skipping
            // it quietly means the operator's count of permitted hosts and the
            // deployment's do not agree.
            assertThat(refusalFor("")).contains("empty entry");
        }

        @Test
        @DisplayName("an entry of only whitespace is refused as empty")
        void whitespaceOnlyIsRefused() {
            assertThat(refusalFor("   ")).contains("empty entry");
        }

        @Test
        @DisplayName("a CSP keyword is refused, because 'null' matches sandboxed documents")
        void cspKeywordIsRefused() {
            // 'null' is the trap: it reads as a sensible default and it matches
            // sandboxed and data: documents — the ones an attacker controls.
            assertThat(refusalFor("null")).contains("CSP keyword");
            assertThat(refusalFor("'self'")).contains("CSP keyword");
        }

        @Test
        @DisplayName("something that is not a URI at all is refused")
        void nonUriIsRefused() {
            assertThat(refusalFor("not a uri")).contains("not a URI");
        }

        @Test
        @DisplayName("a scheme that is not http or https is refused")
        void otherSchemesAreRefused() {
            assertThat(refusalFor("ftp://files.example.com")).contains("http or https");
            assertThat(refusalFor("file:///etc/passwd")).contains("http or https");
        }

        @Test
        @DisplayName("a bare host with no scheme is refused")
        void schemelessIsRefused() {
            assertThat(refusalFor("app.example.com")).contains("http or https");
        }

        @Test
        @DisplayName("a trailing slash is refused, and the message shows what was meant")
        void trailingSlashIsRefused() {
            // The one that fails open. A browser sends no trailing slash, so
            // this matches nothing — the setting appears configured and the
            // control does nothing at all.
            assertThat(refusalFor("https://app.example.com/"))
                .contains("more than an origin")
                .contains("https://app.example.com");
        }

        @Test
        @DisplayName("a path is refused")
        void pathIsRefused() {
            assertThat(refusalFor("https://app.example.com/viewer"))
                .contains("more than an origin");
        }

        @Test
        @DisplayName("a query string is refused")
        void queryIsRefused() {
            assertThat(refusalFor("https://app.example.com?tenant=1"))
                .contains("more than an origin");
        }

        @Test
        @DisplayName("credentials in the URL are refused")
        void userInfoIsRefused() {
            // Rebuilding the origin and comparing catches this, where checking
            // the path alone would not — and an origin carrying a password is
            // a secret in a config file (§0.4).
            assertThat(refusalFor("https://user:secret@app.example.com"))
                .contains("more than an origin");
        }

        @Test
        @DisplayName("the refusal quotes the entry, so the operator can find it")
        void refusalQuotesTheOffendingEntry() {
            assertThat(refusalFor("https://app.example.com/viewer"))
                .contains("https://app.example.com/viewer");
        }

        @Test
        @DisplayName("the refusal names the setting it came from")
        void refusalNamesTheSetting() {
            // Two lists are validated by the same code, and an operator with a
            // stack trace and no setting name has to guess which.
            assertThat(refusalFor("ftp://x.example.com"))
                .contains("cde.web.embed-parent-origins");
        }

        @Test
        @DisplayName("a bad document origin names its own setting, not the other one")
        void documentOriginNamesItsOwnSetting() {
            var properties = new WebSecurityHeadersProperties();
            properties.setEmbedDocumentOrigins(List.of("https://contoso.example/"));

            assertThatThrownBy(properties::requireValidOrigins)
                .hasMessageContaining("cde.web.embed-document-origins");
        }

        @Test
        @DisplayName("one bad entry among good ones still stops the deployment")
        void oneBadEntryIsEnough() {
            assertThatThrownBy(() -> withEmbedParents(
                "https://good.example.com", "https://bad.example.com/", "https://also.example.com")
                .requireValidOrigins())
                .hasMessageContaining("https://bad.example.com/");
        }
    }

    @Nested
    @DisplayName("what the settings say about themselves")
    class Interrogation {

        @Test
        @DisplayName("no allowed origins means no cross-origin callers")
        void noCallersByDefault() {
            assertThat(new WebSecurityHeadersProperties().hasCrossOriginCallers()).isFalse();
        }

        @Test
        @DisplayName("a named origin means there are cross-origin callers")
        void namedOriginMeansCallers() {
            assertThat(withAllowedOrigins("https://app.example.com").hasCrossOriginCallers())
                .isTrue();
        }

        @Test
        @DisplayName("no embed parents means framing is refused altogether")
        void noEmbeddingByDefault() {
            // The default has to be refusal: a viewer that frames anywhere is
            // a clickjacking surface on somebody else's page.
            assertThat(new WebSecurityHeadersProperties().hasEmbeddingHosts()).isFalse();
        }

        @Test
        @DisplayName("a named embed parent means framing is permitted")
        void namedParentMeansEmbedding() {
            assertThat(withEmbedParents("https://cde.customer.example").hasEmbeddingHosts())
                .isTrue();
        }
    }

    @Nested
    @DisplayName("the transport-security defaults")
    class TransportDefaults {

        @Test
        @DisplayName("strict transport security is on unless switched off")
        void hstsOnByDefault() {
            assertThat(new WebSecurityHeadersProperties().isHstsEnabled()).isTrue();
        }

        @Test
        @DisplayName("the default max-age is the year §5.4 asks for")
        void hstsMaxAgeIsAYear() {
            assertThat(new WebSecurityHeadersProperties().getHstsMaxAgeSeconds())
                .isEqualTo(31_536_000L);
        }

        @Test
        @DisplayName("no report address is configured by default")
        void noReportUriByDefault() {
            // Sending policy violations somewhere is a deployment's choice; a
            // default address would be an egress nobody asked for (§9.3).
            assertThat(new WebSecurityHeadersProperties().getCspReportUri()).isEmpty();
        }
    }
}
