# 15. Serve the browser application from the backend image

- **Status:** Accepted 2026-09-14
- **Date:** 2026-09-14
- **Related:** ADR 12 (viewer as a standalone product), ADR 14 (iframe embed
  and identity), CLAUDE.md §2 (web tier), §5.4 (CSP), §5.13.7–8 (user content
  off the web root), §9.1 (Docker), §9.3 (cloud-agnostic and on-premises)

## Context

ADR 14 landed the `frame-ancestors` allow-list that decides who may put
`/embed` in an iframe. Its own documentation then had to record that the
setting could not yet do anything useful:

> As the manifests stand, `k8s/ingress.yaml` routes everything to this service
> and the image contains no frontend, so **nothing currently serves `/embed`
> at all**.

That is the gap. The pipeline built the Angular bundle and discarded it. The
ingress routed `/` to this service. The service answered every page request
with a 404. A customer following the integration guide would configure the
allow-list correctly, point their iframe at `/embed`, and get nothing.

Two properties of the embed make this more than a packaging detail.

**The document and its policy header must agree.** §5.4 forbids
`style-src 'unsafe-inline'`, and Angular injects component styles as `<style>`
elements at runtime, so the document needs a CSP nonce and the response needs a
header naming the same value. Whatever serves the document must therefore also
emit the header. Split them across two components and the deployment has two
places to keep in step, with a failure mode — every component style refused —
that looks like a broken stylesheet rather than a security header.

**`frame-ancestors` is emitted by whoever serves the document**, not by
whoever holds the configuration. A web tier serving the bundle would need its
own copy of the allow-list, and ADR 14's validation — which refuses wildcards,
paths and the null origin, because an unparseable source silently widens the
policy — would not apply to that copy.

### What §2 says

> | Web tier | **Apache HTTP Server** | TLS termination, reverse proxy,
> compression, **static assets** |

Taken literally, static assets are Apache's job and this ADR contradicts the
table. §0.7 says to flag that rather than pick a side quietly, so it is flagged
here.

The tension is real but narrower than it looks: §2 describes the hosted
service, where there is an operations team, an Apache in front, and one
deployment. ADR 12 committed to a second shape — the viewer as a product a
customer installs, next to their own CDE — and §9.3 requires that shape to work
on-premises and air-gapped, with "the same image runs everywhere".

## Options

**A. Keep the frontend out of the image; the customer serves it.**
Faithful to §2. The customer receives a tarball and an instruction to configure
their web tier for SPA fallback routing, a CSP with a per-request nonce, and a
`frame-ancestors` list matching a value in our configuration. Every one of
those is a way for an install to go wrong invisibly, and support cannot see any
of it. The nonce requirement makes it worse than the usual static-hosting
story: correct SPA hosting is well understood, but "rewrite a token into the
document on every response and put it in a header too" is not something a
customer's Apache does without bespoke configuration.

**B. A second image containing Apache and the bundle.**
Keeps static files at the web tier and keeps the install to "run these", but
it splits the document from its policy header across two containers and adds a
component to build, scan, sign, patch and ship. The customer still has two
things to run and now an internal routing concern between them.

**C. Serve the bundle from this image, behind whatever web tier exists.**
One artefact. The document and its header come from the same place by
construction. Apache, where a deployment has one, keeps every other job §2
gives it — TLS, compression, caching, rate limiting — and reverse-proxies as it
already does.

## Decision

**Option C.** This image can serve the browser application, controlled by
`cde.web.app.path`, and **it is empty by default** — an unset path means the
image serves no pages and behaves exactly as it did before this ADR.

- The **document** is rendered per request by `BrowserApplicationController`
  so it can carry the CSP nonce that `ContentSecurityPolicyNonce` generates and
  `SecurityConfig` names in the header. It is sent `Cache-Control: no-store`:
  a cached copy would reach a visitor whose response names a different nonce.
- **The nonce is substituted into a placeholder, not injected as an
  attribute.** The frontend's `src/index.html` declares
  `ngCspNonce="CDE_CSP_NONCE"`, which makes the Angular build stamp that
  literal onto everything needing a nonce — the inlined critical CSS, the
  module scripts, and the small script that promotes the deferred stylesheet —
  and this server replaces every occurrence. Writing the attribute onto
  `<app-root>` here instead would have covered the styles Angular injects at
  runtime and none of those.
- **The nonce is on `script-src` as well as `style-src`.** A production build
  emits exactly one inline script, the few lines that promote the deferred
  stylesheet once it loads. This is what §5.4 means by a nonce-based policy and
  is not a step towards `'unsafe-inline'`: an injected script carries no nonce
  and is still refused.
- **Everything else** — scripts, styles, fonts, assets — is a static file with
  a one-year immutable cache, which is safe only because the Angular build
  fingerprints those names.
- **Routes are enumerated, not wildcarded.** A catch-all would answer paths the
  API is supposed to refuse, turning a 404 problem document into an HTML page
  and making a misspelled endpoint look like it exists. The cost is that a new
  client-side route must be added in two places; `BrowserApplicationRoutesTest`
  asserts they agree, because the failure is otherwise quiet — the page serves
  with the API's `default-src 'none'` over it and renders blank.
- **The bundle is staged, not built here.** The Angular sources are in a
  sibling repository, which a Docker build context cannot reach.
  `scripts/stage-browser-app.sh` copies an already-built bundle into `web/`,
  and the Dockerfile copies that. `web/` holds only a `.gitkeep` in the
  repository: committing built output would put a second, silently stale copy
  of the frontend in this history.
- **Uploaded files are not served this way and must not be.** §5.13.7–8 keeps
  user content off any directly-served path; it continues to go through the
  storage abstraction and its authorising endpoint, on its own origin.

## Consequences

**The embed works end to end for the first time.** One image, one configuration
value per concern: `cde.web.app.path` to serve the viewer,
`cde.web.embed-parent-origins` to say who may frame it. The gap ADR 14 had to
document is closed.

**The hosted service is unaffected unless it opts in.** Leave the path unset
and Apache serves the bundle exactly as §2 describes.

**A misconfiguration fails at startup rather than at a customer's browser.**
A path naming a directory with no `index.html` — overwhelmingly, an image built
without staging — refuses to boot and names the script. An `index.html` with no
`<app-root>` to carry the nonce also refuses, because that one otherwise serves
perfectly and renders every component style refused.

**Static files are now served by application threads**, which §7.4 puts at the
edge. For an on-premises install of a document viewer this is not the
bottleneck; for the hosted service it is Apache's job and this stays unset
there. A deployment that sets it and also fronts it with Apache should let
Apache cache the fingerprinted assets, which it can, because they are
immutable.

**The embed's `connect-src` is wider than the application's own.** The embed
document permits `connect-src 'self' https:`, because the document URL is
minted by the integrator on their own storage and is not knowable when this
image is built. It is scoped to the embed route, still refuses plain `http:`,
and a deployment that knows its integrators' storage origins should narrow it.

**Verified against a real bundle in a real browser, and it found two faults
that reasoning had not.** A production build was served under the composed
policy and driven with Chromium on `/`, `/login` and `/embed`:

1. The first policy had `script-src 'self'` with no nonce. The build's inline
   stylesheet-promoting script was refused, so the deferred main stylesheet
   was never promoted from `media="print"` and **never applied at all** — the
   page rendered with only the inlined critical CSS and looked broken rather
   than unstyled. Fixed by putting the nonce on `script-src`.
2. `/embed` threw `NG0201` before rendering anything, unrelated to the CSP:
   `ViewerStateService` is deliberately not a root service, and nothing on the
   route provided it. Fixed in the frontend by scoping `ViewerStateService`,
   `EmbedSession` and `HostChannel` to `EmbedViewerComponent`, with a spec that
   creates the component through its own providers rather than a hand-built
   injector — which is how the existing unit tests passed while the route could
   not start.

All three routes now load with zero CSP violations, the inlined critical CSS
and the main stylesheet both applied. Still unverified: the pdf.js worker and a
cross-origin document fetch, which need a document to open.

**The image is larger**, by the size of the bundle. §7.1 caps the initial JS at
250 KB gzipped, so this is tens of megabytes at most including lazy chunks, and
it replaces a second artefact rather than adding one.

**Two copies of the route list.** Accepted deliberately, with a test that fails
when they diverge — see the decision above.
