# 14. Embed the viewer in an iframe, and give it no identity of its own

- **Status:** **Proposed.** Not yet accepted — see "A note on this status".
- **Date:** 2026-09-09
- **Related:** ADR 12 (viewer as a standalone product), ADR 9 (bundle
  third-party JavaScript), `cde-angular/docs/viewer-extraction-inventory.md`,
  CLAUDE.md §5.4, §5.5, §5.13.8

## Context

ADR 12 decided the viewer becomes a product a customer installs, fed by a
URL the integrator mints. It left two questions inside that decision, and
named neither as an ADR: **how does a host mount the viewer**, and **how does
the viewer learn who the user is**.

Everything downstream waits on them. The demo application cannot be written,
because a demo is a demonstration of the integration contract and there is
none. The seventeen document operations cannot be decided one at a time,
because whether an operation belongs to the viewer or the host depends on
what the host can be asked to do. Markup persistence cannot be designed,
because where markup goes is the same question.

### What is actually coupled

The extraction inventory counted **26 endpoints across 9 services**, by
following the imports rather than grepping a directory. That number has been
got wrong twice in this engagement — once as "seven", once as "fourteen" —
both times by scanning `features/viewer/` and not the services it imports.
The method matters more than the number: the viewer's surface is what its
transitive imports reach, not what its own directory contains.

They group into three, and the grouping is the design:

| Group | Count | What it is |
|---|---|---|
| Content | 5 | Getting the document and its geometry |
| Document operations | 17 | Pages, redaction, signing, forms, OCR, versions, flatten |
| Identity | 2 | Login and register — not the viewer's business at all |

Annotations sit inside the content group on the read path and inside document
operations on the write path, which is the first hint that "read" and "write"
is the seam that matters.

### What is already true

Two things have changed since the inventory was written and it is stale on
both:

**The API origin is configurable.** `API_BASE_URL`, an injection token
consumed by `apiBaseUrlInterceptor`, with `collaboration.service` applying it
by hand for the socket because an interceptor never sees one. Empty by
default, so same-origin deployment needs no configuration. The inventory
calls this "the single most pervasive change" and lists it as step 1 of five;
it is done.

**`@cde/viewer-core` is a buildable package** — six services and five
components with no network dependency, enforced by a boundary test rather
than a convention.

So the viewer *can already* talk to an origin that is not the page's. That
widens the options rather than settling them: "the host does all the I/O" is
no longer the only shape that works.

### The property that decides it

**The viewer renders untrusted customer documents.** PDF.js parses hostile
input by design; §5.13.10 already says not to process untrusted files
in-process with heavy native parsers, and a PDF renderer in a browser tab is
exactly that, moved to the client.

§5.13.8 requires that *we* serve user-uploaded content from a separate origin
so a stored HTML or SVG payload cannot execute against the application
origin. Embedding our viewer in a customer's page inverts the same problem
and hands it to them: if the viewer shares the host's origin, a malicious
document that escapes pdf.js runs with the host's session, on the host's
domain, against the host's API. We would be shipping our customers the
vulnerability our own §5.13.8 exists to prevent.

That is not a tie-breaker among otherwise equal options. It rules some out.

## Options — how a host mounts the viewer

**A. An Angular library the host imports.** What we have internally today.
Cheapest, and useless as a product: it requires the host to *be* an Angular
application on our major version. Procore, Asite and Dalux are three
different stacks and none of them is going to move to Angular 22 because we
did. Rejected on reach, before the security argument is reached.

**B. A custom element (`@angular/elements`), `<cde-viewer>`.**
Framework-agnostic, attributes in and `CustomEvent`s out, Angular hidden
inside. Genuinely attractive: the host writes one tag, styling and layout
compose naturally, focus and printing behave like the rest of their page.

It fails on the origin argument. A custom element runs in the host's
document, in the host's origin, under the host's CSP. Our bundle and the
host's bundle share a global scope and a JavaScript context. So does any
document we render. It also puts our Angular version in their page, which is
a compatibility surface with no boundary — two Angular versions in one
document is a known-bad configuration and we cannot control which one they
have.

**C. An `<iframe>` and a versioned `postMessage` protocol.** The host embeds a
URL we serve; everything crosses an explicit, serialisable boundary. The
origin boundary is a real security boundary enforced by the browser, not a
convention. Our framework version is invisible. A crash, a memory leak, or a
document that escapes pdf.js is contained in a document that has no session
and no API of the host's to reach.

Costs, and they are real: iframes are awkward about sizing, focus management,
printing, and full-screen; the protocol is a version-compatibility surface we
own forever; and `frame-ancestors 'none'` (§5.4) has to become an allow-list.

**D. Ship only the headless core; the host builds the UI.** Maximum
flexibility, and not a product — it is a library, and the integration guide
becomes "write a viewer". It also gives up the accessibility work, which is a
procurement gate (§1A) and one of the more valuable things in the codebase.

## Options — how the viewer learns who the user is

**A. The host passes a signed token the viewer validates.** Real identity in
the viewer: JWKS distribution, key rotation, clock skew, audience checks. It
buys the viewer the ability to make trustworthy authorisation decisions of
its own — which, per §5.5, it is not allowed to make anyway, because
client-side checks are UX only.

**B. The host passes claims in the handshake, unsigned.** A display name, a
subject id, a set of capability flags. Spoofable by whoever controls the host
page — which is the host, who already has the document and already decided to
show it. The user cannot forge it without already controlling the host's
page, at which point the viewer is not the weak link.

**C. The viewer holds no identity at all.** It emits "markup created" with no
author; the host stamps identity server-side from its own session.

## Decision

**Embed: option C. An `<iframe>` and a versioned `postMessage` protocol, as
the one supported way to embed the viewer.** `@cde/viewer-core` stays
published as the escape hatch for an integrator who wants to build their own
interface, and is documented as exactly that — not the recommended path, and
not covered by the accessibility conformance claim.

The origin boundary is the decision. Everything else about iframes is a cost
we pay for it.

**Identity: B and C together, split on what the claim is for.**

> **The viewer never authenticates anyone and never authorises anything.**
>
> The host tells it a name to display and a set of controls to render. Those
> claims are presentation, are marked untrusted in the protocol, and are
> never the basis of a security decision. Every operation the viewer emits is
> authorised by the host, server-side, against the host's own session.

This is not a compromise between A and C — it is what §5.5 already requires,
written down. "Client-side checks are UX only. Never trust a role claim from
the browser without re-validating server-side." A signed token in the viewer
would let the viewer make a decision it must not make, and would cost key
distribution for the privilege.

The concrete surface is small, which is the evidence that this is the right
size. The viewer needs three things and no more, from three call sites:

| Need | Used for | Trust |
|---|---|---|
| Display name | The label on markup the user creates | Presentation |
| Stable subject id | "Is this reply mine?", and suppressing the echo of one's own collaboration events | Presentation |
| Capability flags | Which controls to render — `canDelete` is the only one today | **UX only.** The host re-checks on the operation |

There is no token, no session, no login, no tenant. The two identity
endpoints in the inventory leave with the viewer rather than moving into it.

### The rule for the seventeen document operations

Deciding them one at a time was the plan, and the plan was wrong: seventeen
separate decisions is seventeen chances to be inconsistent. One rule:

> **An operation that only reads or computes belongs to the viewer. An
> operation that changes the document is a host callback.**

The viewer holds markup in `ViewerStateService` and emits it; it does not
persist it. Search, measurement, outline, page navigation and rendering are
viewer-side because they are computations over bytes it already has.

**Signing and versioning are host callbacks with no exception**, for a reason
that generalises: a signature over a copy the host has since replaced is
worse than no signature. The viewer holds a fetched copy and cannot know
whether it is still current. Any operation whose correctness depends on the
document being the live one belongs to whoever owns the live one.

## Consequences

**`frame-ancestors 'none'` must become an allow-list, and that is a security
change, not a configuration change.** `SecurityConfig` sets it in two places
and `SecurityHeadersTest` asserts it in two. The embed endpoint — and only
that endpoint — needs `frame-ancestors` naming the integrator origins for
that tenant, sourced from tenant configuration rather than a wildcard. Every
other route keeps `'none'`. A wildcard here would undo the isolation the
whole decision rests on, so the test that currently asserts `'none'`
everywhere should become one that asserts `'none'` everywhere *except* the
embed route, and asserts the embed route is never `*`.

**The postMessage protocol is a published API and needs the treatment of
one.** Versioned, with an explicit handshake, an origin check on every
message in both directions, and a schema. §3.4's rules about backwards
compatibility apply to it exactly as they apply to REST: additive changes
within a version, and a deprecation window. It should be specified in the
integration guide before it is implemented, because the guide is where its
awkwardness will show first.

**The accessibility work needs re-testing across the boundary, not
inheriting.** §1A is a procurement gate and the ACR has to be traceable to
test results. Focus entering and leaving an iframe, the tab sequence,
`aria-label` on the frame, printing, 200% zoom and 320px reflow inside a
frame the host sizes — none of that is covered by the existing tests, which
run the viewer as a page. Some of it will be worse than it is today. That
cost is real and is accepted here rather than discovered during an audit.

**Collaboration needs its own authentication.** The socket URL is already
derived from the configured origin, so the base-URL half is done. The other
half is not: the STOMP connection currently rides the page's session, and in
an embedded deployment there is no such session. This is the one place the
"no identity" decision has a loose end, and it should be closed with a
short-lived token the host mints for the socket — not by giving the viewer a
session after all.

**The demo application becomes buildable**, which is the point. It
demonstrates the iframe embed, the handshake, and a host that persists
markup — three sample files, Office, PDF and IFC, with DWG requiring the
operator-supplied ODA converter (ADR 13).

**What this does not decide.** Whether the viewer is served from our
infrastructure or the customer's — both work with an iframe, and it is a
deployment question. What the protocol's messages actually are. Whether
`@cde/viewer-core` is published publicly or handed to integrators directly,
which is the `UNLICENSED` decision in `cde-angular/docs/licences.md` §3.4.

## A note on this status

The README for this directory says an ADR is a record, not a proposal, and
this one is marked **Proposed**. That is a deliberate exception and it should
stay rare.

The reason is that this was written to give a decision something concrete to
react to, rather than to record one already taken. Marking it Accepted would
misrepresent who decided it; leaving it unwritten would leave four pieces of
work blocked on a conversation nobody had scheduled. ADR 13 went the same way
in reverse — the engineering recommendation there was overridden, correctly,
by a product decision — and the same is likely and welcome here.

Accept it, amend it, or replace it; but the status should not stay Proposed
for long, because a proposal in the ADR directory is indistinguishable from a
decision to anyone reading quickly.
