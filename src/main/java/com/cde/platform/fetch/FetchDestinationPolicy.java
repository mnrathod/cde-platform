package com.cde.platform.fetch;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;

/**
 * Whether a URL supplied by an integrating application may be fetched.
 *
 * <p>ADR 12 has the host CDE mint a short-lived link — a Graph download URL,
 * an S3 presigned GET, an Azure SAS, a GCS signed URL — and hand it to us to
 * fetch. That is what lets four storage platforms collapse to one code path
 * without us holding anyone's credentials. It also means a caller chooses an
 * address that this server then connects to, which is server-side request
 * forgery in its textbook form (§5.12 A10).
 *
 * <p>What makes it dangerous is not the internet: it is that this server sits
 * inside a network the caller cannot otherwise reach. The cloud metadata
 * endpoint at {@code 169.254.169.254} hands out instance credentials to
 * anything that asks from the instance itself. The database, the converter,
 * Actuator on the internal interface — all are one HTTP request away from a
 * process that will make requests on request.
 *
 * <p><strong>The address is checked, never the string.</strong> A name is not
 * a destination: {@code evil.example} can resolve to {@code 127.0.0.1}, and
 * checking the text of the host would pass it. Every resolved address is
 * checked, and a name that resolves to several is only allowed if all of them
 * are, because which one the connection uses is not ours to choose.
 *
 * <p>This class decides; it does not connect. Keeping the judgement separate
 * from the transport is what makes it testable without a network, and the
 * reason every rule below has a test naming the attack it stops.
 */
public final class FetchDestinationPolicy {

    /**
     * Schemes that may be fetched.
     *
     * <p>An allow-list, so {@code file:}, {@code gopher:}, {@code ftp:} and
     * {@code jar:} are refused by omission rather than by remembering to name
     * them. {@code file:} is the one that matters: it turns this from a
     * network fetch into an arbitrary file read.
     */
    private static final Set<String> PERMITTED_SCHEMES = Set.of("https", "http");

    private final boolean requireTls;
    private final List<String> permittedHosts;

    /**
     * @param requireTls     when true, {@code http} is refused as well. The
     *                       default for any real deployment: a presigned URL
     *                       carries its own authorisation in the query string,
     *                       and sending that in clear text hands it to anyone
     *                       on the path.
     * @param permittedHosts exact host names that may be fetched, or empty to
     *                       allow any host that passes the address rules. A
     *                       tenant that can name its storage hosts should:
     *                       the address rules stop us reaching our own
     *                       network, and an allow-list additionally stops us
     *                       being pointed at somebody else's.
     */
    public FetchDestinationPolicy(boolean requireTls, List<String> permittedHosts) {
        this.requireTls = requireTls;
        this.permittedHosts = List.copyOf(permittedHosts);
    }

    /**
     * @param resolver how to turn a host name into addresses. Injected so a
     *                 test can describe a name that resolves to a private
     *                 address — the rebinding case — without needing DNS to
     *                 cooperate.
     * @throws FetchNotPermittedException stating which rule refused it, in
     *                 terms the integrator can act on (§1.4). The message
     *                 names the rule, never the resolved address: telling a
     *                 caller *which* internal address their name reached is
     *                 the network-mapping oracle this exists to deny.
     */
    public void checkPermitted(URI target, HostResolver resolver) {
        if (target.getScheme() == null || !PERMITTED_SCHEMES.contains(lowercase(target.getScheme()))) {
            throw new FetchNotPermittedException(
                "Only https URLs can be fetched. Supply a link with an https scheme.");
        }
        if (requireTls && !"https".equals(lowercase(target.getScheme()))) {
            throw new FetchNotPermittedException(
                "Only https URLs can be fetched, because a signed link carries its "
              + "authorisation in the URL and http would expose it in transit.");
        }

        String host = target.getHost();
        if (host == null || host.isBlank()) {
            // A URI can parse without one — `https:///path`, or a malformed
            // authority. Reaching the resolver with null is a crash rather
            // than a refusal, and a crash is a worse answer than "no".
            throw new FetchNotPermittedException(
                "The link has no host. Supply a complete URL.");
        }

        if (!permittedHosts.isEmpty() && !permittedHosts.contains(lowercase(host))) {
            throw new FetchNotPermittedException(
                "The host " + host + " is not on this deployment's list of permitted "
              + "storage hosts. Ask an administrator to add it.");
        }

        List<InetAddress> addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new FetchNotPermittedException("The host " + host + " could not be resolved.");
        }
        if (addresses.isEmpty()) {
            throw new FetchNotPermittedException("The host " + host + " resolved to no address.");
        }

        // Every address, not the first. A name that resolves to one public
        // and one private address is a rebinding attack with the work done
        // for it: refusing only when the first happens to be private makes
        // the check a coin toss.
        for (InetAddress address : addresses) {
            if (isUnreachableByPolicy(address)) {
                throw new FetchNotPermittedException(
                    "The host " + host + " resolves to an address inside this "
                  + "deployment's own network, which cannot be fetched.");
            }
        }
    }

    /**
     * @return whether this address belongs to the network the server sits in
     *     rather than to the internet.
     */
    private boolean isUnreachableByPolicy(InetAddress address) {
        return address.isLoopbackAddress()        // 127/8, ::1 — us
            || address.isLinkLocalAddress()       // 169.254/16 — cloud metadata
            || address.isSiteLocalAddress()       // 10/8, 172.16/12, 192.168/16
            || address.isAnyLocalAddress()        // 0.0.0.0, ::
            || address.isMulticastAddress()
            || isUniqueLocalIpv6(address);
    }

    /**
     * IPv6 unique-local addresses, {@code fc00::/7}.
     *
     * <p>{@link InetAddress#isSiteLocalAddress()} does not report them: it
     * answers for the deprecated {@code fec0::/10} range instead. So the
     * private-network check that looks complete for IPv4 has a hole in IPv6
     * exactly where a modern private network lives.
     */
    private boolean isUniqueLocalIpv6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    private static String lowercase(String value) {
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    /** How a host name becomes addresses. */
    @FunctionalInterface
    public interface HostResolver {
        List<InetAddress> resolve(String host) throws UnknownHostException;
    }
}
