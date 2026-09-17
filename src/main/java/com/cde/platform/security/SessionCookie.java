package com.cde.platform.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * The browser's session, carried where a script cannot read it.
 *
 * <p>The web client used to keep its JWT in {@code localStorage} and send it as
 * a bearer header. §4.6 forbids that in as many words — "No JWTs in
 * localStorage" — and the reason is narrow and practical: {@code localStorage}
 * is readable by any JavaScript running on the origin, so a single cross-site
 * scripting bug anywhere in the application hands over a token good for the
 * whole of its lifetime, on every device the attacker cares to replay it from.
 * A cookie marked {@code HttpOnly} is not reachable from script at all, so the
 * same bug can make requests as the user but cannot walk away with their
 * session.
 *
 * <p>The {@code __Host-} prefix is not decoration. A browser refuses to accept
 * a cookie under that name unless it is {@code Secure}, has {@code Path=/} and
 * carries no {@code Domain} — which means a sibling subdomain, including one
 * an attacker has managed to stand up, cannot set a cookie this application
 * would then read as its own session.
 *
 * <p>{@code SameSite=Lax} stops the cookie riding along on cross-site POSTs,
 * which removes most of CSRF; §5.4 requires a token as well, and
 * {@code SecurityConfig} adds one for exactly the requests this cookie
 * authenticates.
 *
 * <p>Bearer tokens still work. The mobile SDKs authenticate that way by
 * published contract, and a native client has no cookie jar to protect — this
 * is additive, not a replacement.
 */
@Component
public class SessionCookie {

    /**
     * The cookie's name, prefix included.
     *
     * <p>Always prefixed, with no unprefixed fallback for local development.
     * §5.3 is "HTTPS everywhere, no exceptions", and browsers treat
     * {@code localhost} as a secure context, so a development origin accepts a
     * {@code Secure} cookie without one. Offering a non-secure variant would
     * mean the thing developers exercise daily is not the thing that ships.
     */
    public static final String NAME = "__Host-cde_session";

    private final long lifetimeMillis;

    public SessionCookie(com.cde.platform.config.JwtProperties jwtProperties) {
        this.lifetimeMillis = jwtProperties.getExpirationMs();
    }

    /**
     * The cookie that starts a session.
     *
     * <p>Its lifetime matches the token's, so the browser stops sending a
     * credential at the moment the server would stop honouring one. A longer
     * cookie would mean every expired session arriving as a 401 the client has
     * to discover; a shorter one would log people out while their token was
     * still good.
     */
    public ResponseCookie issueFor(String token) {
        return base(token).maxAge(Duration.ofMillis(lifetimeMillis)).build();
    }

    /**
     * The cookie that ends one.
     *
     * <p>Every attribute has to match the cookie being replaced or the browser
     * treats it as a different cookie and keeps both — so this is built from
     * the same place rather than written out again beside it.
     */
    public ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    /** The token the browser sent, if it sent one. */
    public Optional<String> readFrom(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
            .filter(cookie -> NAME.equals(cookie.getName()))
            .map(Cookie::getValue)
            .filter(value -> !value.isBlank())
            .findFirst();
    }

    /** Whether this request is authenticated by cookie rather than by header. */
    public boolean authenticatesByCookie(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        boolean bearer = authorization != null && authorization.startsWith("Bearer ");
        return !bearer && readFrom(request).isPresent();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
            .httpOnly(true)
            .secure(true)
            .sameSite("Lax")
            .path("/");
    }
}
