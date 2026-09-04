package com.cde.platform.fetch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the fetch policy refuses.
 *
 * <p>Every test names the attack rather than the branch, because that is what
 * has to keep working. A rule can be rewritten; "we must not fetch the cloud
 * metadata endpoint" cannot.
 *
 * <p>The resolver is supplied per test, so a host name can be made to resolve
 * wherever the case needs — which is the only way to describe DNS rebinding
 * without owning a malicious domain.
 */
class FetchDestinationPolicyTest {

    private static final URI SOMEWHERE = URI.create("https://files.example.test/doc.pdf");

    /** Resolves every name to one address, whatever the name. */
    private static FetchDestinationPolicy.HostResolver resolvingTo(String... addresses) {
        return host -> {
            var resolved = new java.util.ArrayList<InetAddress>();
            for (String address : addresses) {
                resolved.add(InetAddress.getByName(address));
            }
            return List.copyOf(resolved);
        };
    }

    private static FetchDestinationPolicy anyHost() {
        return new FetchDestinationPolicy(true, List.of());
    }

    @Nested
    @DisplayName("addresses inside our own network")
    class PrivateAddresses {

        @Test
        @DisplayName("refuses the cloud metadata endpoint, which hands out instance credentials")
        void refusesCloudMetadata() {
            assertThatThrownBy(() ->
                anyHost().checkPermitted(SOMEWHERE, resolvingTo("169.254.169.254")))
                .isInstanceOf(FetchNotPermittedException.class);
        }

        @Test
        @DisplayName("refuses loopback, which is this server")
        void refusesLoopback() {
            assertThatThrownBy(() ->
                anyHost().checkPermitted(SOMEWHERE, resolvingTo("127.0.0.1")))
                .isInstanceOf(FetchNotPermittedException.class);
        }

        @Test
        @DisplayName("refuses IPv6 loopback, not only the IPv4 spelling of it")
        void refusesIpv6Loopback() {
            assertThatThrownBy(() ->
                anyHost().checkPermitted(SOMEWHERE, resolvingTo("::1")))
                .isInstanceOf(FetchNotPermittedException.class);
        }

        @Test
        @DisplayName("refuses each RFC 1918 range, where the database and converter live")
        void refusesPrivateRanges() {
            for (String address : List.of("10.0.4.17", "172.16.9.1", "192.168.1.5")) {
                assertThatThrownBy(() ->
                    anyHost().checkPermitted(SOMEWHERE, resolvingTo(address)))
                    .describedAs("address %s", address)
                    .isInstanceOf(FetchNotPermittedException.class);
            }
        }

        @Test
        @DisplayName("refuses IPv6 unique-local, which isSiteLocalAddress does not report")
        void refusesUniqueLocalIpv6() {
            // fc00::/7 is where a modern private network lives, and the JDK's
            // site-local check answers for the deprecated fec0::/10 instead —
            // so the obvious implementation has a hole exactly here.
            assertThatThrownBy(() ->
                anyHost().checkPermitted(SOMEWHERE, resolvingTo("fd00::1")))
                .isInstanceOf(FetchNotPermittedException.class);
        }

        @Test
        @DisplayName("refuses the unspecified address")
        void refusesAnyLocal() {
            assertThatThrownBy(() ->
                anyHost().checkPermitted(SOMEWHERE, resolvingTo("0.0.0.0")))
                .isInstanceOf(FetchNotPermittedException.class);
        }

        @Test
        @DisplayName("allows an ordinary public address")
        void allowsPublic() {
            assertThatCode(() ->
                anyHost().checkPermitted(SOMEWHERE, resolvingTo("93.184.216.34")))
                .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("the address, not the name")
    class Rebinding {

        @Test
        @DisplayName("refuses a public-looking name that resolves somewhere private")
        void refusesRebinding() {
            // The whole reason the check is on resolved addresses. Nothing
            // about "files.example.test" looks wrong.
            assertThatThrownBy(() ->
                anyHost().checkPermitted(SOMEWHERE, resolvingTo("10.0.0.5")))
                .isInstanceOf(FetchNotPermittedException.class);
        }

        @Test
        @DisplayName("refuses when any one of several addresses is private")
        void refusesWhenAnyAddressIsPrivate() {
            // Which address the connection picks is not ours to choose, so
            // allowing this because the first is public would make the check
            // a coin toss rather than a control.
            assertThatThrownBy(() ->
                anyHost().checkPermitted(SOMEWHERE, resolvingTo("93.184.216.34", "127.0.0.1")))
                .isInstanceOf(FetchNotPermittedException.class);
        }

        @Test
        @DisplayName("refuses a name that resolves to nothing")
        void refusesEmptyResolution() {
            assertThatThrownBy(() ->
                anyHost().checkPermitted(SOMEWHERE, host -> List.of()))
                .isInstanceOf(FetchNotPermittedException.class);
        }

        @Test
        @DisplayName("refuses a name that does not resolve")
        void refusesUnresolvable() {
            assertThatThrownBy(() ->
                anyHost().checkPermitted(SOMEWHERE, host -> {
                    throw new UnknownHostException(host);
                }))
                .isInstanceOf(FetchNotPermittedException.class);
        }
    }

    @Nested
    @DisplayName("schemes")
    class Schemes {

        @Test
        @DisplayName("refuses file:, which would make this an arbitrary file read")
        void refusesFileScheme() {
            assertThatThrownBy(() -> anyHost().checkPermitted(
                URI.create("file:///etc/passwd"), resolvingTo("93.184.216.34")))
                .isInstanceOf(FetchNotPermittedException.class);
        }

        @Test
        @DisplayName("refuses anything not on the allow-list, without naming it")
        void refusesExoticSchemes() {
            for (String url : List.of("ftp://h.example.test/x", "gopher://h.example.test/x",
                                      "jar:file:///x!/y")) {
                assertThatThrownBy(() ->
                    anyHost().checkPermitted(URI.create(url), resolvingTo("93.184.216.34")))
                    .describedAs("url %s", url)
                    .isInstanceOf(FetchNotPermittedException.class);
            }
        }

        @Test
        @DisplayName("refuses http when TLS is required, because a signed link is a credential")
        void refusesPlainHttpWhenTlsRequired() {
            assertThatThrownBy(() -> anyHost().checkPermitted(
                URI.create("http://files.example.test/doc.pdf"), resolvingTo("93.184.216.34")))
                .isInstanceOf(FetchNotPermittedException.class);
        }

        @Test
        @DisplayName("allows http only where a deployment has opted out of TLS")
        void allowsHttpWhenPermitted() {
            var relaxed = new FetchDestinationPolicy(false, List.of());
            assertThatCode(() -> relaxed.checkPermitted(
                URI.create("http://files.example.test/doc.pdf"), resolvingTo("93.184.216.34")))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("refuses a URL with no host rather than failing inside the resolver")
        void refusesHostlessUrl() {
            assertThatThrownBy(() -> anyHost().checkPermitted(
                URI.create("https:///just/a/path"), resolvingTo("93.184.216.34")))
                .isInstanceOf(FetchNotPermittedException.class)
                .hasMessageContaining("no host");
        }
    }

    @Nested
    @DisplayName("host allow-list")
    class HostAllowList {

        private final FetchDestinationPolicy restricted =
            new FetchDestinationPolicy(true, List.of("files.example.test"));

        @Test
        @DisplayName("allows a named host")
        void allowsNamedHost() {
            assertThatCode(() ->
                restricted.checkPermitted(SOMEWHERE, resolvingTo("93.184.216.34")))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("refuses a public host that is not named")
        void refusesUnnamedHost() {
            assertThatThrownBy(() -> restricted.checkPermitted(
                URI.create("https://elsewhere.example.test/doc.pdf"),
                resolvingTo("93.184.216.34")))
                .isInstanceOf(FetchNotPermittedException.class);
        }

        @Test
        @DisplayName("matches the host case-insensitively, as DNS does")
        void matchesHostCaseInsensitively() {
            assertThatCode(() -> restricted.checkPermitted(
                URI.create("https://FILES.EXAMPLE.TEST/doc.pdf"),
                resolvingTo("93.184.216.34")))
                .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("what a refusal says")
    class RefusalMessages {

        @Test
        @DisplayName("never reveals the address a host resolved to")
        void doesNotLeakTheResolvedAddress() {
            // A caller who learns that their name reached 10.0.4.17 has been
            // handed a network map one refusal at a time. The message may say
            // that the address was internal; it may not say which.
            assertThatThrownBy(() ->
                anyHost().checkPermitted(SOMEWHERE, resolvingTo("10.0.4.17")))
                .isInstanceOf(FetchNotPermittedException.class)
                .satisfies(thrown ->
                    assertThat(thrown.getMessage()).doesNotContain("10.0.4.17"));
        }

        @Test
        @DisplayName("says what to do next, not merely that it failed")
        void explainsTheRemedy() {
            assertThatThrownBy(() -> anyHost().checkPermitted(
                URI.create("http://files.example.test/doc.pdf"), resolvingTo("93.184.216.34")))
                .hasMessageContaining("https");
        }
    }
}
