package com.cde.platform.fetch;

import java.net.URI;

/**
 * Whether a URL may be fetched at all.
 *
 * <p>Exists so that {@link RemoteContentFetcher} depends on the *question*
 * rather than on {@link FetchDestinationPolicy}'s answer. The fetcher is
 * transport — size caps, timeouts, redirects — and its tests need to reach a
 * loopback server, which the real policy refuses on purpose and must go on
 * refusing.
 *
 * <p>The alternative would be an {@code allowPrivateAddresses} flag on the
 * policy, and a security control with a switch marked "off" is a control that
 * is one misconfigured deployment away from absent. Splitting the interface
 * costs one file and keeps the policy with no way to say yes to
 * {@code 169.254.169.254}.
 */
@FunctionalInterface
public interface DestinationCheck {

    /**
     * @throws FetchNotPermittedException if this URL must not be fetched,
     *         with a message the integrator can act on
     */
    void check(URI target);
}
