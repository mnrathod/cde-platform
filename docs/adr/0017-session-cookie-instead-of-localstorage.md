# 17. The browser session moves out of localStorage

Date: 2026-09-17

## Status

Accepted.

## Context

The web client signed in, received a JWT in the response body, and kept it in
`localStorage`. Every subsequent request attached it as
`Authorization: Bearer <token>`.

§4.6 forbids this in as many words — "No JWTs in `localStorage`" — and the
reason is narrow and practical rather than a matter of taste. `localStorage`
is readable by any script running on the origin. One cross-site scripting bug
anywhere in the application, in any dependency, on any page, yields a token
that is valid for its whole lifetime and replayable from anywhere. Nothing
else about the session had to be weak for that to be true: a single injected
`<script>` was enough to take a working credential away with it, and nothing
on the server could tell the difference afterwards.

A token in an `HttpOnly` cookie is not reachable from script at all. The same
XSS can still make requests as the user while the page is open — that is not
what a cookie fixes — but it cannot exfiltrate the session.

The complication is that the token is not only a browser concern. The mobile
SDKs authenticate with `Authorization: Bearer`, documented in
`mobile-sdk/API-CONTRACT.md`, and a native client has no cookie jar worth
protecting and no origin to be scripted from.

## Decision

**Issue both. Change only what the browser does.**

Login and registration set a `__Host-cde_session` cookie —
`HttpOnly; Secure; SameSite=Lax; Path=/` — alongside the existing body token.
`JwtFilter` accepts either, preferring an explicit `Authorization` header over
an ambient cookie. Nothing is removed from the API, so the SDK contract holds.

The `__Host-` prefix is load-bearing rather than decorative: a browser refuses
a cookie under that name unless it is `Secure`, has `Path=/` and names no
`Domain`, which means a sibling subdomain — including one an attacker stands
up — cannot set a cookie this application would read as its own session.

Three things had to change with it, and none of them are optional.

**CSRF protection, scoped.** Disabling CSRF was defensible while a bearer
header was the only credential, because a browser does not attach one by
itself and a cross-site form therefore had nothing to forge with. A cookie
rides along uninvited. `SameSite=Lax` stops most of it and §5.4 asks for a
token as well, so one is now required — but only for state-changing requests
authenticated *by cookie*. Requiring it of bearer callers would break the SDK
contract to defend against an attack those clients cannot suffer.

**Two new endpoints.** `GET /api/auth/session` and `POST /api/auth/logout`
exist because the cookie is unreadable by script: the client can no longer
parse the subject out of its own token, and cannot delete what it cannot see.
Both are consequences of the cookie, not conveniences.

**The WebSocket endpoint's origin list.** `setAllowedOriginPatterns("*")` was
safe for exactly as long as the CONNECT frame's bearer token was the only
credential. Once the handshake's cookie authenticates the socket, a wildcard
is cross-site WebSocket hijacking — any page a user visits could open a socket
as them and read a project's collaboration traffic. The endpoint now names its
origins, and registers no pattern at all when none are configured, which
leaves same-origin only.

## Consequences

**The client knows less, and asks more.** Identity comes from
`/api/auth/session` on startup rather than from decoding a stored token, which
is one request per page load. The route guard has to wait for it, where it
used to read a signal synchronously — getting that wrong would bounce every
signed-in user to the login screen on every refresh.

**`Secure` has a deployment consequence.** The cookie is refused over plain
HTTP. §5.3 is "HTTPS everywhere, no exceptions" and browsers treat
`localhost` as a secure context, so development is unaffected — but a
deployment terminating TLS upstream must not serve the application origin over
HTTP internally without the proxy setting `X-Forwarded-Proto`.

**Logout is now a request, and can fail.** The client clears its own state and
navigates regardless, because stranding someone on a page they appear to be
signed out of is worse than a session that outlives the click. A server that
never heard the logout leaves the cookie alive until it expires.

**This does not revoke anything.** A bearer token stays valid until it
expires; ending a session clears the browser's copy. Revocation before expiry
needs the revocation list §4.6 describes separately, and is not in this
change.

**XSS is mitigated, not solved.** An injected script can still act as the user
for as long as the page is open. What it can no longer do is walk away with
the session. The CSP in §5.4 remains the control that stops the injection in
the first place.
