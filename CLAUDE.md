# CLAUDE.md

Operating manual for Claude Code on this repository. Read this before writing, reviewing, or refactoring any code. Rules here override generic best-practice defaults.

> **Amendments for this codebase.** This document is the governing standard,
> with two deliberate deviations recorded here rather than left as silent
> contradictions:
>
> 1. **The frontend is Angular, not React.** §2 originally specified React +
>    Vite. The existing application is Angular with a working PDF/CAD viewer and
>    markup layer under test; rewriting it would deliver no user value and
>    discard verified code, against §0.6. Every other frontend rule —
>    accessibility, performance budgets, lazy loading, the generated API client,
>    no business logic in components — applies unchanged, and the framework-
>    specific lines have been translated rather than dropped.
> 2. **The backend builds with Gradle**, not Maven. §9.2's build stage is
>    written accordingly.
>
> Anything else that conflicts with the code is a defect in the code, not a
> licence to ignore this document.

---

---

## 0. How Claude Code must behave in this repo

1. **Never invent a version number.** Before adding or upgrading any dependency, resolve the current stable release and its API via the **Context7 MCP** (`resolve-library-id` → `query-docs`), and check support status at `endoflife.date`. If Context7 is unavailable, say so and ask — do not guess.
   - The tool names changed in Context7 v4. The second tool is **`query-docs`**, not `get-library-docs`, and **`resolve-library-id` requires both `query` and `libraryName`** — passing `libraryName` alone is rejected. Read the schema from `tools/list` rather than working from these names; this line is prose and the server is the authority.
   - Configured in `.mcp.json` at each repository root (stdio, pinned to `@upstash/context7-mcp@4.0.3`). See `/docs/tooling.md` for why stdio rather than the hosted HTTP endpoint, and how to add a personal API key without committing one.
2. **No deprecated, EOL, or EOS components.** This includes transitive dependencies flagged by the SCA scan. If the only way to complete a task is with an EOL component, stop and raise it.
3. **Ask before introducing a new dependency.** State the license, maintenance status, last release date, transitive footprint, and why an existing dependency can't do the job.
4. **Never write secrets, tenant identifiers, customer data, or real tokens into code, tests, fixtures, logs, or commit messages.**
5. **Every change ships with tests.** A PR that lowers coverage below threshold is not done.
6. **Prefer deleting code to adding it.** Prefer configuration to code. Prefer a boring solution to a clever one.
7. **When a requirement in this file conflicts with a request in the prompt, flag the conflict and ask.** Do not silently pick one.
8. **Definition of Done is section 16.** Self-check against it before declaring a task complete.

---

## 1. Product principles (User Experience)

### 1.1 Show only what is relevant
- Default to the **minimum viable interface**. Every field, column, button, and menu item must justify its presence against a real user task. If it serves <10% of users, it belongs behind "Advanced", a per-user preference, or an admin-only surface.
- **Progressive disclosure**: primary action visible, secondary actions one click away, destructive/rare actions two.
- Filter by permission and context: never render a control the current user cannot use. Hide it, don't disable it — unless the disabled state teaches something ("Requires Admin role"), in which case disable with a tooltip explaining why.
- Empty states must teach, not apologise. Show what the object is, why it's empty, and the single next action.
- Tables: sensible default column set, user-configurable, persisted per user per tenant. Never show 30 columns by default.

### 1.2 Minimum configuration by the end user
- **Every setting must have a working default.** A new tenant must be able to complete the core workflow within 10 minutes of signup with zero configuration.
- Setting hierarchy: `system default → tenant override → team override → user override`. Implement this once, centrally; do not scatter per-feature config lookups.
- Derive rather than ask: infer timezone, locale, date format, and units from the browser/IdP claims, then let the user correct.
- Configuration screens must show the effective value **and** where it came from ("Inherited from tenant policy").
- If a feature needs more than three decisions to switch on, it needs a guided setup wizard with sane pre-selections, not a settings page.

### 1.3 Learn from the market
Before designing any non-trivial screen or flow, look at how mature products solve it and note the convention you're following:
- **Google Workspace** — navigation, search-first interaction, keyboard shortcuts, inline editing, undo-over-confirm.
- **Microsoft 365 / Azure Portal** — admin console structure, RBAC assignment UX, resource hierarchy, audit log presentation.
- **Salesforce Lightning** — list views, bulk actions, record detail layout, permission sets, sharing rules.
- **Atlassian / Stripe / Linear** — onboarding, empty states, API + docs quality, error message tone.
- Direct competitors in our domain — feature parity table maintained in `/docs/competitive-analysis.md`.

Rules that fall out of this:
- Follow platform conventions for anything users already know (Ctrl/Cmd+K search, `/` to focus search, Esc to close, autosave with explicit "Saved" indicator).
- **Undo beats confirmation dialogs** for reversible actions. Reserve modals for irreversible ones and require typed confirmation for destructive tenant-level operations.
- Never invent new interaction patterns for solved problems.

### 1.4 Non-negotiable UX baseline
- Responsive from 360px up.
- Error messages state **what happened, why, and what to do next** — never a raw stack trace, never a bare error code. Include a correlation ID the user can quote to support.
- Optimistic UI with rollback for fast actions; skeletons (not spinners) for loads >300ms; no layout shift.
- Every long-running operation is cancellable and reports progress.
- i18n from day one: no hardcoded user-facing strings; ICU message format; RTL-safe layout.

---

## 1A. Accessibility — WCAG 2.2 Level AA, mandatory

**Accessibility is a procurement gate, not a nice-to-have.** Every public-sector buyer we are targeting requires demonstrable conformance, and several require it by law. Without a current Accessibility Conformance Report we cannot bid. Treat an accessibility defect as a functional defect at the same severity, not as a backlog item.

### 1A.1 Standards we conform to

| Jurisdiction / driver | Requirement | Notes |
|---|---|---|
| **Baseline (everywhere)** | **WCAG 2.2 Level AA** | The single technical target. Conforming to 2.2 AA satisfies the others below. |
| UK public sector / MOD | Public Sector Bodies Accessibility Regulations 2018; **EN 301 549** | Requires WCAG AA plus a published accessibility statement |
| EU | **EN 301 549**; European Accessibility Act | EN 301 549 is the harmonised procurement standard |
| Australia | Disability Discrimination Act 1992; Digital Service Standard | Australian Government requires WCAG AA for digital services |
| United States | **Section 508** (revised); ADA Title III | Buyers request a VPAT-based conformance report |
| Evidence artefact | **VPAT 2.x → Accessibility Conformance Report (ACR)** | Use the INT edition covering WCAG, Section 508 and EN 301 549 in one document |

Verify the current version of each before a bid — these move, and the EU/UK positions in particular have shifted recently.

### 1A.2 Build rules
- **Semantic HTML first.** A `<button>` is a button. ARIA is used only where native semantics are insufficient, never to paper over a `<div>` with a click handler. Bad ARIA is worse than none.
- **Everything works from the keyboard**, in a logical order, with no traps. Visible focus indicators that meet the 2.2 focus-appearance criteria — never `outline: none` without a stronger replacement.
- **Contrast**: 4.5:1 for body text, 3:1 for large text, UI components and graphical objects. Colour is never the sole carrier of meaning (status, validation, chart series all need a second cue).
- **Target size ≥ 24×24 CSS px** (WCAG 2.2 SC 2.5.8) with adequate spacing; we use ≥ 44px for primary touch targets as a stricter internal rule.
- **Every drag interaction has a single-pointer alternative** (SC 2.5.7) — this matters for our Kanban-style and reordering UI, and for any model or drawing markup tool.
- **Focus is never obscured** by sticky headers, cookie banners, or toolbars (SC 2.4.11).
- **Help is in a consistent place** across pages (SC 3.3.2), and **information already entered is not re-requested** (SC 3.3.7) in multi-step flows like onboarding, upload metadata, and approval workflows.
- Form controls are labelled and programmatically associated; errors are announced, identify the field, and describe the fix.
- Dynamic updates use live regions appropriately; route changes move focus and announce the new context.
- Respect `prefers-reduced-motion` and `prefers-contrast`; support 200% zoom and 320px reflow without horizontal scrolling; text spacing overrides must not break layout.
- Never suppress zoom or lock orientation.

### 1A.3 Accessible authentication — interacts directly with §4
**WCAG 2.2 SC 3.3.8 (Accessible Authentication, Level AA) prohibits a cognitive function test as the only means of authentication.** This constrains decisions already made in §4, so implement accordingly:
- **Password fields must allow paste** and work with password managers. Blocking paste is both an accessibility failure and worse for security.
- **OTP/TOTP entry must allow paste** of the full code and support autofill. Do not use split single-character inputs that break paste and screen-reader flow.
- **CAPTCHA cannot be the only path through lockout or rate limiting** (§4.2). Provide a non-cognitive alternative — email or authenticator confirmation. Prefer risk-based throttling over CAPTCHA generally.
- **WebAuthn/passkeys are the most accessible strong factor** (SC 3.3.8 explicitly favours object/biometric recognition) — another reason they are first-class in §4.4.
- Session timeout warnings must be announced, extendable, and give enough time (SC 2.2.1); never silently discard work on expiry.
- Recovery codes must be selectable and copyable text, not an image.

### 1A.4 Content types that are commonly missed
- **Data tables**: proper headers and scope, caption, and a sortable/filterable pattern that announces state. Virtualised tables (§7.3) must still expose correct row/column counts to assistive technology.
- **Charts and visualisations**: every chart has an accessible text summary and a **data-table equivalent**. A canvas or SVG chart alone is not accessible.
- **File upload**: keyboard-operable without drag and drop, with progress and completion announced, and clear error text for rejected files (§5.13).
- **The 3D/IFC model viewer is the hardest case and needs an explicit decision.** A WebGL canvas cannot be made conformant on its own. The requirement is an **equivalent accessible route to the same information** — a structured, navigable tree and table of the model hierarchy, properties, and metadata that supports search, selection, and the same actions as the viewer. Build this as the primary data interface with the viewer as a visual layer over it, not as an afterthought bolted on later.
- **Generated documents and exports**: PDFs and reports produced by the product must be **tagged and PDF/UA-conformant** — reading order, alt text, table structure, document language. An inaccessible export is an accessibility failure of the product, and this is the single most common gap found in government audits.
- **Transactional and notification emails** follow the same rules: semantic structure, alt text, contrast, no meaning carried by images alone.
- Any video or training content ships with captions and a transcript.

### 1A.5 Testing
- **Automated checks are necessary but not sufficient** — axe and similar tools reliably catch only a minority of WCAG issues. Passing automated checks is not conformance and must never be reported as such.
- **CI gates**: axe-core via Playwright across critical journeys, plus Lighthouse accessibility budgets. Zero violations at serious or critical severity on `main`.
- **Manual keyboard-only pass** on every new or changed screen, by the developer, before review. This is part of the Definition of Done, not a QA hand-off.
- **Screen reader testing** on the supported matrix before each release: NVDA and JAWS on Windows, VoiceOver on macOS and iOS, TalkBack on Android. Test against the browser each is normally paired with.
- **Independent audit annually** and before any major UI change, by a specialist third party, to the same cadence and remediation SLAs as penetration testing (§5.9): Critical 7 days, High 30, Medium 90.
- **Usability testing with disabled users** at least annually. An interface can pass every success criterion and still be unusable; only real users surface that.

### 1A.6 Documentation and evidence
- **Published accessibility statement**: conformance level claimed, known limitations with plain-language impact, remediation timeline, feedback mechanism, and last-review date. UK public sector regulations require this specifically.
- **VPAT/ACR maintained per release**, not written in a panic during a bid. Every claim in it must be traceable to a test result.
- **Known issues are tracked in the normal backlog with an accessibility label** and an owner — not in a private document.
- A monitored feedback channel with a committed response time; user-reported accessibility issues are triaged like security reports.
- Accessibility evidence joins the SOC 2 and ISO 27001 evidence pack (§5.1) — the same automated-collection principle applies.


---

## 2. Technology stack

| Layer | Choice | Notes |
|---|---|---|
| Backend | **Spring Boot 3.x** on current Java LTS | Verify exact versions via Context7 / endoflife.date |
| Frontend | **Angular** (TypeScript, strict mode) | Standalone components; see the amendment note above |
| Database | **PostgreSQL** (current supported major) | Row-Level Security for tenant isolation |
| Web tier | **Apache HTTP Server** | TLS termination, reverse proxy, compression, static assets |
| Cache | **Valkey** (BSD-3-Clause) | Redis-protocol compatible, Linux Foundation governed. Cache-aside, tenant-scoped keys |
| Messaging | **ActiveMQ Artemis** (Apache-2.0) | Default and only broker. Kafka adopted only on a documented trigger (§7.8) |
| Containers | **Docker**, multi-stage, non-root, distroless/minimal base | |
| CI/CD | **Jenkins** declarative pipelines | Pipeline-as-code in `Jenkinsfile` |
| IdP / SSO | **Keycloak** (Apache-2.0) | SAML 2.0, OIDC, TOTP, brokering |
| Native code | **C++ (or Rust)** via JNI / Java FFM API | Only under §12.6 conditions |

### 2.1 Licence policy — hard rule
**Allowed:** Apache-2.0, MIT, BSD-2/3-Clause, ISC, EPL-2.0, MPL-2.0, CDDL, PostgreSQL licence, Unlicense/CC0.

**Forbidden:** **AGPL (any version)**, SSPL, BSL/Business Source Licence, Commons Clause, Elastic Licence, "source available" non-OSI licences, and anything with a field-of-use restriction. LGPL is permitted **only** for dynamically linked, unmodified libraries and must be approved case-by-case.

- A licence scan (`license-maven-plugin` / `license-checker` / Syft) runs in CI and **fails the build** on any forbidden licence, including transitive.
- GPL tools used as **separate, out-of-process services** invoked over a socket or CLI (e.g. ClamAV, GPLv2) are acceptable — do not link or embed them.
- Maintain `/docs/licences.md` with the approved list and every exception granted.

### 2.2 Rejected components and why — do not reintroduce
These were evaluated and **excluded** for licence reasons. Do not add them, do not suggest them, and do not accept them as transitive dependencies.

| Component | Licence problem | Use instead |
|---|---|---|
| **Couchbase Server** | Business Source Licence (BSL 1.1) on recent majors — not OSI open source | **Valkey** (BSD-3-Clause) |
| **Redis** | Re-licensed to RSALv2/SSPL, later AGPL tri-licence — AGPL is excluded | **Valkey** (BSD-3-Clause) |
| **Grafana** | AGPLv3 | **Prometheus** (Apache-2.0) + **Perses** (Apache-2.0) |
| **Elasticsearch / Kibana** | AGPL | **OpenSearch** (Apache-2.0) |
| **MongoDB** | SSPL | PostgreSQL (JSONB) or **FerretDB** (Apache-2.0) |
| **Terraform ≥1.6** | BSL 1.1 | **OpenTofu** (MPL-2.0) |
| **HashiCorp Vault ≥1.15** | BSL 1.1 | **OpenBao** (MPL-2.0), or cloud-native KMS/secret stores |

Valkey specifics: Redis-protocol compatible (Lettuce/Jedis and Spring Data Redis work unchanged), Linux Foundation governed, BSD-3-Clause, actively maintained. Deploy in cluster mode with replicas, TLS enabled, ACL/AUTH enforced, and persistence configured per cache tier — **but treat it as a cache, never as the system of record**. Every key still carries a TTL and a tenant namespace (§7.6).

Claude Code: if a task appears to require a rejected component, **stop and surface this table** rather than adding the dependency.

---

## 3. Architecture and code quality

### 3.1 Structure
- **Modular monolith by default.** Package by feature (`com.acme.product.invoicing.…`), not by layer (`…controllers`, `…services`). Extract a service only when there's a proven scaling, deployment-cadence, or team-ownership reason — document it in an ADR.
- Layering within a feature: `api` (controllers, DTOs) → `application` (use cases, transactions) → `domain` (entities, rules, no framework imports) → `infrastructure` (JPA, clients, adapters).
- **Dependencies point inward.** `domain` must not import Spring, JPA, Jackson, or HTTP types. Enforce with ArchUnit tests in CI.
- All external systems (object storage, mail, LLM, virus scan, IdP) sit behind an interface owned by `domain`/`application`, with implementations in `infrastructure`. This is what makes §11 (pluggable storage) and §13 (multi-cloud) possible.
- Every significant decision gets an ADR in `/docs/adr/NNNN-title.md` (context, options, decision, consequences).

### 3.2 Naming — meaningful, always
- **Classes**: noun phrases stating role — `InvoiceReconciliationService`, `TenantScopedJdbcTemplate`. Banned: `Manager`, `Helper`, `Util`, `Data`, `Info`, `Processor`, `Handler` as the *only* qualifier.
- **Methods**: verb phrases stating the effect — `reserveLicenceSeat()`, `hasExceededUploadQuota()`. Booleans read as predicates (`is/has/can/should`). Methods that mutate say so.
- **Variables**: no single letters except loop indices and lambda params of ≤3 lines. No abbreviations except universally understood ones (`id`, `url`, `http`). `elapsedMillis`, not `t`.
- Units and currency in the name or the type: `timeoutSeconds`, `sizeBytes`, `Money amountAud` — never a bare `int amount`.
- Names must not lie. If a method's behaviour changes, rename it in the same commit.

### 3.3 Reusable and maintainable
- **Rule of three**: duplicate twice, abstract on the third. Do not build a framework for one caller. Wrong abstractions cost more than duplication.
- Shared concerns live in explicit modules: `platform-security`, `platform-tenancy`, `platform-audit`, `platform-storage`, `platform-messaging`, and **`platform-cde`** (the ISO 19650 Common Data Environment — information containers, revisions, state machine and transitions, per §6.7). Feature code consumes them; feature code never re-implements them.
- **`platform-cde` is built before the features that depend on it.** The container/revision model and its state machine are foundational: every document-touching feature, permission check, and audit trail assumes them. Building features first and retrofitting the CDE means migrating all of it.
- Functions: one job, ≤ 40 lines, ≤ 4 parameters (use a parameter object beyond that), cyclomatic complexity ≤ 10. Nesting ≤ 3 — return early.
- Files ≤ 400 lines. Angular components ≤ 200 lines; extract services and child components.
- **No business logic in controllers, templates, or SQL.** Controllers validate and delegate. Components render and dispatch; business logic lives in services.
- Immutability by default: Java `record`s for DTOs, `final` fields, no setters on domain entities — behaviour methods that enforce invariants instead.
- Fail fast: validate at the boundary, never pass `null` across a module boundary (use `Optional` on return types), throw domain-specific exceptions.
- **No commented-out code, no `TODO` without a ticket ID, no dead flags.** Git remembers.
- Static analysis gates the build: Checkstyle/Spotless, SpotBugs, PMD, ESLint + Prettier, TypeScript `strict` with `noUncheckedIndexedAccess`. Zero warnings on `main`.

### 3.4 API design
- REST, versioned by path (`/api/v1/...`). Backwards-compatible changes only within a version; additive fields only. Deprecations announced via `Deprecation`/`Sunset` headers with ≥6 months notice.
- Consistent pagination (cursor-based for large sets), filtering, sorting, and a standard error envelope (RFC 9457 Problem Details) including `traceId`.
- Idempotency keys on all POST endpoints that create resources or move money.
- Every endpoint declares its required permission; there is no "default allow".
- Resource-oriented URLs (plural nouns, no verbs), correct HTTP semantics (`GET` safe and idempotent, `PUT` idempotent, `PATCH` via JSON Merge Patch, `DELETE` idempotent), correct status codes (`201` + `Location`, `202` for async, `409` for conflicts, `422` for validation, `429` with `Retry-After`).
- Long-running work returns `202 Accepted` with a job resource the client can poll or subscribe to (§7.1).

### 3.5 OpenAPI — required for all core functionality
**Every endpoint that is part of core functionality must appear in the OpenAPI specification, fully described. An endpoint absent from the spec, or present but under-specified, fails the build.** The spec is a first-class deliverable, not documentation exhaust.

**Approach**
- **OpenAPI 3.1** (JSON Schema 2020-12 compatible), generated with **springdoc-openapi** from annotated controllers and DTOs, then linted and diffed. Code-first keeps the spec honest — it cannot drift from the implementation because it is derived from it.
- The generated spec is **committed to the repo** at `/api/openapi.yaml` on every build. A PR that changes API behaviour without changing the committed spec fails CI. This makes every API change visible in code review as a diff.
- Split by domain with `$ref` composition if the file exceeds ~5,000 lines; publish a bundled single file for consumers.

**Coverage requirements — all mandatory, CI-enforced**
- [ ] Every path, method, and parameter documented with a `summary`, a `description`, and an `operationId` (stable, camelCase, used for client generation — never auto-numbered).
- [ ] Every request and response body has a **named, reusable schema** in `components/schemas` — no inline anonymous objects.
- [ ] Every schema property has a `type`, a `description`, and where applicable `format`, `enum`, `minLength`/`maxLength`, `minimum`/`maximum`, `pattern`, `nullable`, and `readOnly`/`writeOnly`. **The schema constraints must match the Bean Validation annotations on the DTO** — a validation rule that exists in code but not in the spec is a bug.
- [ ] Every operation documents **all** response codes it can return, including `400`, `401`, `403`, `404`, `409`, `422`, `429`, and `500`, each referencing the shared `ProblemDetail` schema.
- [ ] Every operation declares its `security` requirement and, in the description, the **specific permission** it requires (§5.5).
- [ ] **Realistic examples** on every request and response — synthetic data only, never production data, never real names or emails (§6, §14). Examples are validated against their own schemas in CI.
- [ ] Pagination, filtering, and sorting parameters described consistently via shared `components/parameters`.
- [ ] Deprecated operations marked `deprecated: true` with the sunset date in the description.
- [ ] Tags group operations by domain, with tag descriptions, so generated docs are navigable.

**Security schemes** declared in `components/securitySchemes`: session cookie, OAuth 2.0 (authorization code + PKCE for user-facing clients, client credentials for machine clients) with all scopes enumerated and described, and API key (header). Document tenant scoping and rate-limit headers (`X-RateLimit-*`, `Retry-After`) in the top-level `info.description`.

**Core functionality that must be covered end to end**
Authentication and session lifecycle · MFA enrolment and verification · SSO/SAML/OIDC tenant configuration · SCIM 2.0 user and group provisioning · tenant and organisation management · user, role, and permission management · audit log query and export · file upload (including multipart/resumable), download, and metadata · async job submission, status, and cancellation · search and listing endpoints · notification and webhook configuration · data export and data-subject-request endpoints (§6.2) · health and status · every core business domain resource.

**Pipeline enforcement** (stages in §9.2)
- **Lint**: Spectral with a custom ruleset encoding the checklist above — build fails on error-level findings.
- **Breaking-change detection**: `oasdiff` (or equivalent) compares the PR spec against the released spec for the same major version. A breaking change inside a stable version fails the build; it must go to a new version or be made additive.
- **Contract tests**: every response in the integration test suite is validated against its schema at runtime (`atlassian-oas-validator` or `schemathesis`). Schema drift fails tests, not just linting.
- **Fuzz/property testing**: Schemathesis generates adversarial requests from the spec to find unhandled inputs — a cheap, high-yield security and robustness gate.
- **Client generation**: TypeScript client for the Angular frontend generated from the spec (`openapi-typescript` + generated fetch client) so **the frontend cannot call an endpoint that isn't specified**, and types stay in sync automatically. Java and Python SDKs generated for customers on release.
- **Publishing**: interactive docs (Scalar / Redoc / Swagger UI — all permissively licensed, §17) served at `/api/docs`, versioned per release. **Swagger UI's "try it" console is disabled in production** or restricted to a sandbox tenant; the spec endpoint itself is public but the console is not a production feature.

Claude Code: when adding or changing an endpoint, update the annotations and regenerate the spec **in the same commit**. Never hand-edit `/api/openapi.yaml`.

---

## 4. Authentication

Implement via Keycloak or Spring Security + Spring Authorization Server. **Never hand-roll crypto, session handling, or token validation.**

### 4.1 Hashing standard — SHA-256
**SHA-256 is the mandated hash primitive across the platform.** SHA-384/SHA-512 are acceptable where a longer digest is required. **MD5 and SHA-1 are banned** for any security purpose (the sole exception is the HIBP range API in §4.3, which is a k-anonymity lookup protocol, not a security boundary).

**Password storage: `PBKDF2-HMAC-SHA-256`, minimum 600,000 iterations, 128-bit random per-user salt, 256-bit derived key.**

This is SHA-256 based, FIPS 140-2/140-3 validated, and on the ASD ISM approved algorithm list — which matters directly for IRAP and UK MOD scope (§6.4–6.6), where a FIPS-validated module is often contractually required.

- **A bare, single-round SHA-256 of a password is a security defect and must never be committed.** SHA-256 is designed to be fast, which is exactly what makes offline cracking cheap — a commodity GPU tests billions of raw SHA-256 candidates per second. The iteration count is what makes it safe; the KDF wrapper is not optional. Any code path that calls `MessageDigest.getInstance("SHA-256")` on a password fails review, and a CI grep rule enforces this.
- Tune the iteration count to ~500 ms on production hardware and re-baseline annually as hardware improves. Never reduce it.
- **`Argon2id` is available as a per-deployment option** (`security.password.kdf=pbkdf2-sha256|argon2id`) for tenants not bound by FIPS. It resists GPU/ASIC attack better because it is memory-hard; use OWASP's baseline of ≥19 MiB memory, ≥2 iterations, parallelism 1. Spring Security's `DelegatingPasswordEncoder` supports both simultaneously.
- Store the algorithm identifier and all parameters alongside the hash (`{pbkdf2-sha256}$iterations$salt$hash`) so parameters can evolve. **Transparently re-hash on the next successful login** when the configured parameters change — never force a mass reset.
- Compare hashes in constant time (`MessageDigest.isEqual`), never with `String.equals`.

**SHA-256 is also the standard for**: API key and refresh-token lookup hashing, HMAC signatures, file and artifact integrity, audit-log hash chaining (§5.7), and JWT/TLS signing (RS256/ES256).

### 4.2 Configurable password policy
Per-tenant, admin-configurable, enforced identically on client (for UX) and server (for security):

| Setting | Default | Range |
|---|---|---|
| Minimum length | 12 | 8–128 |
| Maximum length | 128 | ≥64 (never truncate) |
| Complexity classes required | 0 (length-first) | 0–4 (upper/lower/digit/symbol) |
| Block breached passwords | **on** (not disableable) | — |
| Block dictionary / tenant-context words | on | company name, product name, username, email |
| Password history | 5 | 0–24 |
| Minimum age | 1 day | 0–7 |
| **Expiry (mandatory — cannot be disabled)** | **90 days** | 30 / 60 / 90 / 180 / 365 days |
| Expiry warning lead time | 14 days | 1–30 days |
| Failed-attempt threshold | 10 | 3–20 |
| Lockout behaviour | progressive delay | delay / temporary lock / admin unlock |
| Lockout duration | 15 min | 1 min–permanent |
| Idle session timeout | 30 min | 5 min–8 h |
| Absolute session lifetime | 12 h | 1 h–24 h |
| Concurrent sessions per user | unlimited | 1–unlimited |
| Re-auth for sensitive actions | on | — |

Notes:
- **Password expiry is mandatory.** Tenants choose the interval, not whether it applies. There is no "never expires" option and no per-user exemption. Service accounts and API keys expire on their own schedule (§4.6) — they must not be given passwords.
- **Interval: 90 days default, tenant-admin configurable within a deployment-level ceiling.** The ceiling is set at deployment, not by the tenant, so a sovereign or Defence deployment can enforce a shorter maximum that no tenant admin can exceed:

| Deployment tier | Default | Admin may set | Ceiling enforced by |
|---|---|---|---|
| Commercial / standard | 90 days | 30–365 days | Application config |
| Government / IRAP-scoped | 90 days | 30–90 days | Deployment config, admin cannot exceed |
| Defence (UK MOD / Australian Defence) | Per contract | Contract-locked, admin cannot change | Deployment config, change requires redeployment |

- The tenant admin sees the effective value **and where the ceiling came from** (§1.2), so a Defence admin who cannot raise it understands why rather than filing a support ticket.
- Expiry implementation requirements: warn in-app and by email at the configured lead time and daily in the final 3 days; on expiry, force a change at next login (allow authentication, then redirect to a mandatory change screen — do not lock the account); enforce password **history** and **minimum age** so users cannot cycle straight back to the old password; invalidate all other active sessions on change; audit the event.
- Because forced rotation pushes users toward predictable variants (`Summer2026!` → `Autumn2026!`), the compensating controls in this table are load-bearing and must not be weakened: breach checking on every change (§4.3), history depth ≥5, minimum age ≥1 day, similarity check against the previous password, and MFA (§4.4). Track rotation-driven support volume; if it is high, raise the interval rather than dropping the other controls.
- NIST SP 800-63B advises against forced periodic expiry, but ISO 27001 auditors, several government schemes, and many enterprise procurement checklists still require it. We implement it; the compensating controls above are how we avoid its known downside.
- **Lockout is a DoS vector.** Default to progressive delay + CAPTCHA + IP/ASN rate limiting rather than hard account lock. Never reveal whether an account exists or is locked in the login response — generic message, constant-ish timing.
- Rate limit per account, per IP, and per tenant independently.
- Session cookies rotate on privilege change and on login (session fixation defence). Logout invalidates server-side, not just the cookie.

### 4.3 Compromised-password checking (Have I Been Pwned)
Check on registration, on password change, and asynchronously on a rolling schedule for existing users (prompt at next login if found).

- Use the **Pwned Passwords range API with k-anonymity**: compute SHA-1 of the password, send **only the first 5 hex characters** to `https://api.pwnedpasswords.com/range/{prefix}`, match the returned suffixes locally. **The full password and full hash never leave our infrastructure.**
- Send the `Add-Padding: true` header so response size doesn't leak the prefix bucket.
- **Air-gapped / sovereign deployments (UK MOD, Australian Defence, IRAP PROTECTED):** external calls are prohibited. Ship the downloadable Pwned Passwords hash set into a local Bloom filter / local store, refreshed on a controlled schedule. Make the mode a deployment-time setting: `ONLINE_API | LOCAL_DATASET | DISABLED` (`DISABLED` requires an explicit documented risk acceptance).
- Fail-safe policy is configurable: on API timeout, either allow with audit warning (default) or block. Never block silently.
- Timeout ≤ 2s, circuit breaker, no user-visible latency on the happy path.

### 4.4 MFA / TOTP
- **TOTP (RFC 6238)**: SHA-1 or SHA-256, 6 digits, 30s step, ±1 step drift window. Works with Google Authenticator, Microsoft Authenticator, Authy, 1Password.
- Enrolment: QR + manual secret, verify one code before activating. Secrets encrypted at rest with a KMS-held key, never logged, never returned by any API after enrolment.
- **Replay protection**: store the last-used time step per user and reject reuse.
- **Recovery codes**: 10 single-use codes, shown once, stored hashed. Regeneration re-auths.
- **WebAuthn / FIDO2 passkeys** — implement as a first-class second factor and preferred phishing-resistant option. Required for Defence/government tiers.
- SMS is **not** an offered factor.
- Tenant policy: MFA `optional | required-for-admins | required-for-all`, with a grace period and enrolment enforcement at login.
- Step-up MFA for: changing security settings, managing users/roles, exporting bulk data, rotating API keys, changing billing.

### 4.5 SAML 2.0 SSO and OIDC
- **SAML 2.0 SP** support with per-tenant IdP configuration: metadata URL or XML upload, entity ID, ACS URL, signing/encryption certs, NameID format, IdP- and SP-initiated flows, Single Logout.
- **Mandatory validation**: signature on assertion **and** response, certificate chain and expiry, `Destination`, `Audience`, `NotBefore`/`NotOnOrAfter` with ≤3 min clock skew, `InResponseTo`, one-time `ID` replay cache. Disable XML external entities and DTDs. Use a maintained library (Spring Security SAML2 / OpenSAML) — **never parse SAML by hand**.
- Attribute mapping is tenant-configurable: email, first/last name, groups → roles. **Just-in-time provisioning** with a configurable default role, plus **SCIM 2.0** for lifecycle sync (create/update/deactivate).
- Certificate expiry monitoring with alerts at 60/30/7 days to tenant admins.
- Per-tenant flag: `SSO required` (disables password login), with a documented break-glass local admin account that is MFA-protected and heavily audited.
- **OIDC** supported alongside SAML for Entra ID, Okta, Google Workspace, Ping. Authorization Code + PKCE only; implicit flow is banned.
- Home-realm discovery by email domain, with verified domain ownership before a tenant can claim a domain.

### 4.6 Tokens and sessions
- Browser: **HttpOnly, Secure, SameSite=Lax** session cookies with `__Host-` prefix. **No JWTs in `localStorage`.**
- Machine clients: OAuth 2.0 client credentials; short-lived access tokens (≤15 min), rotating refresh tokens with reuse detection (revoke the family on reuse).
- All tokens carry `tenant_id`, `sub`, `scope`, `jti`, `exp`; validated on every request. Revocation list checked for refresh tokens and API keys.
- API keys: prefixed, high-entropy, shown once, stored as SHA-256, scoped, expirable, per-key rate limits and last-used tracking.

---

## 5. Security

### 5.1 Certification posture
Build so evidence is a by-product of engineering, not a fire drill.

- **ISO/IEC 27001**: ISMS with Statement of Applicability mapped to Annex A. Asset register, risk register with treatment plans, supplier/vendor assessments, access reviews (quarterly), documented change management, incident response plan with defined severities and timelines, business continuity plan, security awareness training records. Internal audit + management review cadence.
- **SOC 2 Type II**: controls operating effectively over a 6–12 month observation window across Security (mandatory) plus Availability and Confidentiality. This means **continuous, automated evidence collection** — access provisioning/deprovisioning logs, change approvals tied to tickets, CI security-gate results, vulnerability remediation SLA tracking, backup and restore-test records, uptime/monitoring data. Wire evidence export into the pipeline from day one.
- Map both to a single control set in `/docs/compliance/control-matrix.md` so one control satisfies many frameworks.
- Maintain a customer-facing Trust Centre: sub-processor list, architecture overview, pen test summary, SOC 2 report under NDA.

### 5.2 Encryption at rest
- Database, object storage, backups, snapshots, message queues, search indexes, and log stores are **all** encrypted — **AES-256-GCM** or provider-managed equivalent.
- Keys in a managed KMS/HSM (Azure Key Vault, AWS KMS, GCP KMS, or HashiCorp Vault on-prem). Annual rotation minimum; immediate on suspected compromise.
- **Envelope encryption**: KMS root key → per-tenant data encryption key → data. Enables per-tenant crypto-shredding on offboarding.
- **Application-layer field encryption** for the highest-sensitivity fields (TOTP secrets, integration credentials, personal identifiers, anything classified) so a raw DB dump is insufficient. Use deterministic encryption only where equality search is unavoidable, and document the leakage.
- Bring Your Own Key (BYOK/CMK) available for enterprise, Defence, and government tenants.
- Full-disk encryption on all nodes; no unencrypted volumes or ephemeral scratch space holding customer data.

### 5.3 Encryption in transit
- **TLS 1.3 preferred, TLS 1.2 minimum.** TLS 1.0/1.1, SSLv3, RC4, 3DES, CBC-mode legacy suites, and renegotiation are disabled.
- **HTTPS everywhere, no exceptions**; HTTP redirects to HTTPS with 301 and **HSTS** `max-age=31536000; includeSubDomains; preload`.
- **Internal traffic is encrypted too** — service-to-service mTLS, TLS to Postgres (`sslmode=verify-full`), TLS to Artemis (and Kafka if adopted), TLS to Valkey/cache, TLS to object storage.
- Strong certificate management: automated issuance/renewal, expiry alerting, CAA records, and Certificate Transparency monitoring.
- Target an SSL Labs A+ / equivalent Apache configuration; the TLS config is version-controlled and tested in CI.

### 5.4 Secure cookies and browser hardening
Every response sets:
- `Strict-Transport-Security: max-age=31536000; includeSubDomains; preload`
- `Content-Security-Policy` — nonce-based, `default-src 'self'`, **no `unsafe-inline`, no `unsafe-eval`**, `frame-ancestors 'none'`, `object-src 'none'`, `base-uri 'self'`, with `report-uri` collection
- `X-Content-Type-Options: nosniff`
- `Referrer-Policy: strict-origin-when-cross-origin`
- `Permissions-Policy` denying unused features (camera, microphone, geolocation, USB, payment)
- `Cross-Origin-Opener-Policy: same-origin`, `Cross-Origin-Resource-Policy: same-origin`
- `Cache-Control: no-store` on all authenticated responses

Cookies: `Secure; HttpOnly; SameSite=Lax` (`Strict` for admin surfaces), `__Host-` prefix, scoped path, no sensitive data in cookie values. CSRF: SameSite **plus** synchroniser token / double-submit for state-changing requests.

### 5.5 RBAC
- Model: `User → Role(s) → Permissions`, scoped by `tenant` and optionally by resource group/project. Permissions are fine-grained verbs on resources (`invoice:read`, `invoice:approve`, `tenant.user:manage`).
- **Deny by default.** Every endpoint, every service method, every GraphQL/REST field carries an explicit permission requirement; a missing annotation fails an ArchUnit/integration test.
- System roles (Owner, Admin, Member, Read-only, Auditor) plus **custom tenant-defined roles**. Least privilege for the default role.
- **Authorisation is enforced server-side on every request.** Client-side checks are UX only. Never trust a role claim from the browser without re-validating server-side.
- Guard against IDOR: authorisation checks are object-level, not just endpoint-level — verify the resource belongs to the caller's tenant *and* the caller has the permission on that specific object.
- Privileged actions are time-boxed where possible (just-in-time elevation), fully audited, and surfaced in the tenant's security dashboard.
- Quarterly access reviews exportable per tenant (SOC 2 / ISO 27001 evidence).

### 5.6 Tenant isolation — the highest-severity control
A cross-tenant data leak is a company-ending event. Treat it accordingly.

- **Every table carries `tenant_id NOT NULL`**, indexed, and part of every unique constraint and most composite indexes.
- **PostgreSQL Row-Level Security is enabled and FORCED on every tenant table.** Policies read `current_setting('app.tenant_id')`. The application role is **not** the table owner and does **not** have `BYPASSRLS`.
- `app.tenant_id` is set from the authenticated principal in a single, central connection/transaction interceptor — never from a request parameter, header, or body. It is reset on connection return to the pool.
- **No raw SQL or repository method may filter tenancy manually.** RLS is the backstop; a forgotten `WHERE tenant_id = ?` must be harmless.
- Cache keys, queue topics/message headers, object-storage prefixes, search indexes, temp files, and exported filenames are **all tenant-namespaced**.
- Background jobs, scheduled tasks, and message consumers must establish tenant context explicitly before touching data. A job with no tenant context can only touch system-level tables.
- **Automated cross-tenant tests are mandatory**: for every resource type, an integration test asserts that Tenant A's authenticated principal receives 403/404 (never 200) for Tenant B's object IDs. This suite is a required CI gate.
- Optional stronger isolation tiers for Defence/government: schema-per-tenant, database-per-tenant, or fully dedicated deployment. The storage/tenancy abstraction must support all three without feature-code changes.

### 5.7 Immutable audit logs
- **Log every security-relevant event**: authentication (success/failure/logout/MFA), authorisation denials, user and role changes, permission grants, SSO/SCIM changes, configuration and policy changes, data exports and bulk reads, record create/update/delete with before/after values, API key lifecycle, admin impersonation (start/end/justification), and encryption-key operations.
- Record: timestamp (UTC, ISO-8601), actor (user/service/system + auth method), tenant, source IP + user agent, action, target resource type + ID, outcome, correlation/trace ID, and change delta.
- **Immutability**: append-only table with no `UPDATE`/`DELETE` grants to the application role; a **hash chain** (each record includes SHA-256 of the previous record) so tampering is detectable; periodic export to **WORM storage** (S3 Object Lock in Compliance mode / Azure Immutable Blob with legal hold). Verify the chain on a schedule and alert on breaks.
- **Never log**: passwords, tokens, session IDs, MFA secrets, full payment data, or raw PII bodies. Mask and reference by ID.
- Retention: 12 months hot / 7 years archived by default, configurable up per tenant. Tenant admins get read-only search + export (CSV/JSON) and can stream to their own SIEM.
- Audit logs are written in the same transaction as the change where possible, or via a guaranteed outbox — never best-effort fire-and-forget.

### 5.8 Backup and disaster recovery
- **Targets: RPO ≤ 15 minutes, RTO ≤ 4 hours** for production (state and justify per-tier variations in `/docs/dr-plan.md`).
- Postgres: continuous WAL archiving + PITR, daily full snapshots, cross-region replication **within the permitted data-residency boundary** (§6).
- Object storage: versioning + soft delete + cross-region replication inside the residency boundary.
- Backups are encrypted, access-controlled separately from production credentials, and **immutable** (Object Lock) to survive ransomware and insider deletion.
- **Restore tests are mandatory and automated**: monthly full restore to an isolated environment with data-integrity verification. An untested backup is not a backup. Results are retained as SOC 2 evidence.
- Documented and rehearsed runbooks: region failover, database failover, corrupted-deploy rollback, credential compromise. **DR game day at least annually**, with findings tracked to closure.
- Tenant-level restore capability (restore one tenant without affecting others) and self-service point-in-time export.

### 5.9 Penetration testing
- **Independent third-party penetration test at least annually** and before any major architecture change, covering web app, API, authentication/SSO, multi-tenancy boundaries, and infrastructure. Grey-box with test credentials at multiple privilege levels in at least two tenants.
- Scope must explicitly include **cross-tenant access attempts** and **privilege escalation**.
- Remediation SLAs: Critical 7 days, High 30 days, Medium 90 days, Low next planned release. Retest to confirm closure.
- Continuous internal testing: automated DAST (OWASP ZAP) against a seeded staging environment in the nightly pipeline; authenticated scans, not just unauthenticated crawls.
- Run a responsible disclosure / VDP with a published `security.txt` and a monitored `security@` address; consider a bug bounty once mature.

### 5.10 Vulnerability management
- **SCA on every build**: OWASP Dependency-Check / Trivy / Grype against the SBOM. Build **fails** on Critical/High with a known fix.
- **SAST on every PR**: Semgrep + SpotBugs/find-sec-bugs + ESLint security rules. **Secret scanning** (Gitleaks) on commits and history; pre-commit hooks locally.
- **Container and base-image scanning** on every image build; rebuild and redeploy on base-image CVEs. Images are rebuilt at least weekly regardless of code change.
- **IaC scanning** (Checkov/tfsec) for cloud misconfiguration.
- **SBOM (CycloneDX)** generated and published per release; images signed (Sigstore/cosign) with provenance attestation.
- Patch SLAs: Critical 7 days, High 30, Medium 90, Low 180 — measured from disclosure, tracked in the ticketing system, reported monthly.
- Dependabot/Renovate raises upgrade PRs continuously; the full test suite must pass before merge.
- **Quarterly EOL review**: every runtime, framework, library, base image, and managed service checked against `endoflife.date`. Anything within 6 months of EOL gets a scheduled upgrade ticket.

### 5.11 Security monitoring and detection
- Centralised structured (JSON) logging with correlation IDs, shipped to a SIEM. Logs are tamper-evident and access to them is itself audited.
- **Alert on**: brute-force and credential-stuffing patterns, impossible-travel logins, MFA disable/bypass, privilege escalation, mass export or unusual read volume, admin impersonation, RLS/authorisation denials spiking, config and key changes, new IdP registration, WAF blocks, error-rate and latency anomalies, failed backups, certificate expiry.
- Runtime protection: WAF in front of Apache, rate limiting and bot protection at the edge, DDoS protection, egress filtering from application subnets.
- **Incident response**: documented plan, defined severity levels, named on-call, communication templates, and regulator/customer notification timelines pre-agreed (GDPR 72 hours; Australian Notifiable Data Breach scheme; Defence-specific reporting obligations). Post-incident reviews are blameless and produce tracked actions.
- Security metrics reported to leadership monthly: open vulnerabilities by severity and age, patch SLA compliance, failed logins, access review completion, restore test results.

### 5.12 OWASP Top 10 — concrete requirements

**A01 Broken Access Control** → §5.5, §5.6. Deny by default; object-level checks; automated cross-tenant tests; no client-side-only enforcement.

**A02 Cryptographic Failures** → §4.1, §5.2, §5.3. PBKDF2-HMAC-SHA-256 (≥600k iterations) for passwords, AES-256-GCM at rest, TLS 1.2+ in transit, KMS-managed keys, no home-grown crypto.

**A03 Injection**
- **SQL**: parameterised queries / JPA criteria only. **String-concatenated SQL is forbidden** — CI greps for it. Dynamic ordering/filtering uses an allow-list of column names, never user input interpolated into SQL.
- **NoSQL/N1QL, LDAP, OS command, XPath, template, and header injection**: same rule — parameterise or allow-list, never concatenate. No `Runtime.exec` with user input.
- **XSS**: Angular's default interpolation escaping and built-in DomSanitizer are relied upon; **`bypassSecurityTrustHtml` and direct `innerHTML` binding are banned** without a documented exception and DOMPurify sanitisation. Encode by context (HTML, attribute, JS, URL, CSS). Strict CSP with nonces is the second layer. Set `Content-Type` explicitly and `nosniff`.
- **Server-side validation is mandatory and authoritative.** Client validation exists for UX only. Every request DTO is validated (Jakarta Bean Validation + explicit business rules) with **allow-list** semantics: type, length, range, format, enum membership. Reject unknown JSON properties. Never trust hidden fields, IDs, prices, quantities, roles, or tenant identifiers from the client.
- Mass assignment: bind to explicit DTOs, never to entities.

**A04 Insecure Design** → threat model each feature (STRIDE) before build; abuse cases in the ticket; rate limits and quotas designed in; security requirements in the Definition of Done.

**A05 Security Misconfiguration** → hardened defaults, no default credentials, debug/actuator endpoints locked down (`/actuator` restricted to internal network, only `health`/`info`/`prometheus` exposed), directory listing off, verbose errors off in production, unused features and ports disabled, IaC-scanned infrastructure, config drift detection.

**A06 Vulnerable and Outdated Components** → §5.10 and §0.2.

**A07 Identification and Authentication Failures** → §4 in full: breach checking, MFA, session management, lockout/throttling, no user enumeration, secure password reset (single-use, time-limited, hashed token; invalidate all sessions on reset; notify the user by email).

**A08 Software and Data Integrity Failures** → signed images and artifacts, SBOM, pinned dependency digests, verified checksums, protected `main` with required reviews, no untrusted CDN scripts (self-host, or use SRI), **no `ObjectInputStream`/unsafe deserialisation** of untrusted data.

**A09 Security Logging and Monitoring Failures** → §5.7, §5.11.

**A10 Server-Side Request Forgery** → all outbound URLs derived from user input pass an allow-list; block private/link-local/metadata ranges (169.254.169.254, 10/8, 172.16/12, 192.168/16, ::1, .internal); disable redirect following or re-validate each hop; resolve DNS and validate the resolved IP (guard against rebinding); route egress through a filtered proxy.

### 5.13 File upload security
Uploads are the single most common breach vector in document-centric SaaS. All of the following are required:

1. **Authenticate and authorise** before accepting a byte.
2. Enforce **size limits** (per file, per request, per tenant quota) and reject early via `Content-Length` and streaming limits.
3. **Validate content type by magic bytes / content sniffing**, not by extension or the client-supplied `Content-Type`. Allow-list permitted types per feature.
4. **Antivirus/malware scan every upload — ClamAV** (run as a separate service; GPLv2, out-of-process, so licence-compatible) with signatures auto-updated. Optional pluggable second engine or cloud scanning service for Defence tiers.
5. **Quarantine-first workflow**: write to a quarantine bucket/prefix, scan, then promote to the tenant's storage on clean. The file is not downloadable or referenced by the application until it is clean. Infected files are deleted, the event is audited, and the uploader is notified.
6. **Never trust or reuse the client filename.** Generate a server-side opaque ID for storage; store the original name as metadata only, sanitised, for display and download headers.
7. **Store outside the web root**, always via the storage abstraction (§11) — never on a path served directly by Apache.
8. **Serve from a separate origin/domain** (e.g. `files.example.com`), with `Content-Disposition: attachment`, `X-Content-Type-Options: nosniff`, and a restrictive CSP, so a stored HTML/SVG payload cannot execute against the app origin.
9. **SVG, HTML, and XML uploads are treated as active content** — sanitise or block. Disable XXE/DTD in every XML parser (`FEATURE_SECURE_PROCESSING`, external entities off).
10. **Do not process untrusted files in-process with heavy native parsers** (Office/PDF/image libraries are a rich CVE source). Run extraction, thumbnailing, and rendering in a **sandboxed, resource-limited, network-isolated worker** with a timeout.
11. Prevent zip-bombs and path traversal on archive extraction: cap entry count, uncompressed size, and nesting depth; reject absolute paths, `..`, and symlinks.
12. Strip or preserve EXIF/metadata deliberately (default: strip location data).
13. Downloads use **short-lived pre-signed URLs** scoped to the tenant, or stream through an authorising endpoint. Never expose bucket paths or enumerable IDs.

---

## 6. Data privacy, residency and compliance

### 6.0 Accessibility obligations
Accessibility is a legal and procurement obligation in every jurisdiction we sell into — UK PSBAR 2018, EN 301 549, the Australian DDA and Digital Service Standard, and US Section 508. The technical requirements, evidence artefacts (accessibility statement, VPAT/ACR), and test regime are in **§1A**, which is the single source for this. Treat it as part of the compliance obligations register, not as a UX preference.

### 6.1 Data residency and sovereignty (architectural, not configurational)
- The platform is **region-partitioned**. A tenant is bound to a region at creation, and **all** of its data — primary store, replicas, backups, cache, queues, search indexes, logs, metrics, exports, and temp files — stays inside that region's boundary. Cross-region calls are blocked at the network layer, not just discouraged in code.
- **Australia**: deployable entirely within Australian regions (Azure Australia Central/Central 2/East/Southeast, AWS ap-southeast-2/ap-southeast-4, GCP australia-southeast1/2). Support providers on the **ASD Certified Cloud Services / Hosting Certification Framework** where required.
- Support-team access follows the data: personnel access controls, jurisdiction of support staff, and screening levels are per-region configurable (relevant to Defence and government contracts).
- **Sub-processor register** maintained per region; a tenant in a sovereign region must never have data touched by a sub-processor outside it. Every third-party integration (including AI — §10) must be residency-aware and disableable per tenant.
- Prove it: an automated test/report enumerates every data store and egress destination per region for audit evidence.

### 6.2 GDPR / UK GDPR
- **Lawful basis** recorded per processing activity; Records of Processing Activities (Art. 30) maintained in `/docs/compliance/ropa.md`.
- **Data subject rights implemented as product features, not manual ops**: access/export (machine-readable, Art. 15/20), rectification, erasure (Art. 17) with documented cascade across primary store, backups, caches, logs, search indexes and analytics, restriction, and objection. Target: fulfil within 30 days, self-service where possible.
- **Privacy by design and by default**: collect the minimum, retain the minimum. Every entity has a documented retention period with automated deletion/anonymisation jobs.
- **Consent** where it is the basis: granular, freely given, withdrawable as easily as given, versioned and audited. No pre-ticked boxes. Cookie consent gates all non-essential cookies/analytics.
- **DPA** with Standard Contractual Clauses / UK IDTA for any transfer outside the EEA/UK, plus a transfer impact assessment. Prefer EU/UK-resident processing to avoid the question entirely.
- **DPIA** for high-risk processing (large-scale, sensitive categories, profiling, AI-driven decisions).
- **Breach notification**: 72 hours to the supervisory authority; tooling and templates ready in advance.
- Pseudonymise and encrypt personal data; separate identifiers from behavioural data where feasible.

### 6.3 Australian Privacy Act and the APPs
- Comply with the **13 Australian Privacy Principles**: open and transparent management (APP 1 — published, current privacy policy), anonymity option where practicable (APP 2), collection limited to what's necessary and by lawful/fair means (APP 3), handling of unsolicited information (APP 4), notification at collection (APP 5), use/disclosure limited to the primary purpose (APP 6), direct-marketing controls (APP 7), **cross-border disclosure accountability (APP 8 — we remain accountable for overseas recipients; this is why §6.1 residency partitioning matters)**, data quality (APP 10), **security and destruction/de-identification when no longer needed (APP 11)**, and access and correction rights (APP 12, 13).
- **Notifiable Data Breaches scheme**: assess within 30 days, notify the OAIC and affected individuals for eligible breaches likely to cause serious harm. Runbook and templates maintained.
- Track and implement the **Privacy Act reform tranches** as they commence (expanded individual rights, statutory tort for serious invasions of privacy, automated-decision transparency) — review quarterly; this area is actively changing, so verify current obligations rather than relying on this document.
- Government/health/financial customers may impose additional state-level and sector-specific obligations — capture them per contract in `/docs/compliance/obligations-register.md`.

### 6.4 IRAP and Australian Government workloads
Required for Australian Government and Defence use.

- Design to the **ACSC Information Security Manual (ISM)** control set and the **Protective Security Policy Framework (PSPF)**, targeting the classification the contract requires (typically **OFFICIAL: Sensitive** or **PROTECTED**).
- **IRAP assessment** by a registered IRAP assessor; produce a **System Security Plan (SSP)**, Security Risk Management Plan, Incident Response Plan, and Continuous Monitoring Plan. Maintain an ISM control implementation matrix with evidence per control.
- Implement the **Essential Eight** to the required maturity level: application control, patch applications, configure Office macro settings, user application hardening, restrict administrative privileges, patch operating systems, **multi-factor authentication**, regular backups.
- Cryptography must use **ASD-approved algorithms and ASD-approved cryptographic protocols** (AES-256, SHA-2 family, RSA/ECDSA/ECDH at approved sizes, TLS 1.2+ configured per ISM). Some deployments require FIPS 140-2/140-3 validated modules — make the crypto provider swappable.
- Gateway, network segmentation, and egress control requirements apply; privileged access is separated, logged, and time-bound.
- **Australian data residency and Australian-based, appropriately cleared personnel** for administration and support of these tenants.
- Event logging retention and central log management per ISM; logs stay onshore.

### 6.5 UK Ministry of Defence
- Align to **JSP 440** (Defence Manual of Security), **JSP 604** (network access), and **Def Stan 05-138** cyber security for defence suppliers at the Cyber Risk Profile assigned to the contract, evidenced through the **Supplier Cyber Protection Service / DCPP** questionnaire.
- **Cyber Essentials Plus** as a baseline, plus ISO 27001 (§5.1).
- Handle **OFFICIAL / OFFICIAL-SENSITIVE** per the UK Government Security Classifications Policy: correct marking, handling, storage, and transmission; support for classification labels on records and documents in-product where the contract requires it.
- **UK data residency**, UK-based personnel, and **BPSS (or higher, e.g. SC/DV) clearance** for staff with access, per contract. NCSC Cloud Security Principles addressed and documented.
- Air-gapped / disconnected deployment mode: no outbound internet dependencies (see §4.3 local dataset mode, §10 local-inference mode, offline licence activation, local artifact mirrors).
- Security incident reporting timelines and points of contact per the contract, not just our internal policy.

### 6.6 Australian Defence
- **Defence Industry Security Program (DISP)** membership at the required level (Entity, Personnel, Physical, ICT). Comply with the **Defence Security Principles Framework (DSPF)**.
- ISM/PSPF and Essential Eight as per §6.4, at the classification level specified (up to PROTECTED for most cloud workloads).
- Australian-controlled data, Australian-based infrastructure, and **security-cleared Australian personnel** (Baseline/NV1 as required) for administration and support.
- **Export control awareness**: Defence Trade Controls Act — some technical data may not be transferable offshore or to foreign nationals. Access controls must support nationality/clearance-based restrictions where a contract demands it.
- Physical and personnel security requirements flow to any sub-processor; sovereign deployments run with a restricted, documented supply chain.

### 6.7 ISO 19650 — IN SCOPE, core architecture
**Decision: ISO 19650 is in scope.** The Common Data Environment is therefore a **core architectural requirement designed in from the start**, not a compliance overlay bolted on later. The information container and its revision history are **first-class domain entities**, on the same footing as tenant and user — not an attribute of a file record. Retrofitting this later means migrating every document, every permission, and every audit trail, so it is built first.

Applicable parts: **19650-1** (concepts), **19650-2** (delivery phase), **19650-3** (operational phase / asset information), **19650-4** (information exchange), **19650-5** (security-minded approach), **19650-6** (health and safety information).

#### 6.7.1 The Common Data Environment
Four states with **controlled, gated transitions** — a state machine, not a status field:

| State | Meaning | Who can see it | Transition out requires |
|---|---|---|---|
| **Work in Progress** | Unverified, owned by one task team | Originating task team only | Check / review / approve by the task team |
| **Shared** | Issued for coordination and reference, not approved | Other task teams on the project | Review and authorisation by the lead appointed party |
| **Published** | Authorised for use — the contractual record | Per project permissions, generally all appointed parties | Supersession only (never edit) |
| **Archived** | Superseded or completed; retained as the historical record | Read-only, audit and legal access | Nothing — terminal state |

Requirements:
- **Transitions are the only way state changes.** No direct writes to a state column. Each transition is a domain operation with its own permission, validation, approval record, and audit event (§5.7).
- **Published revisions are immutable.** A change produces a **new revision that supersedes** the previous one; the previous remains retrievable forever. There is no edit and no delete on a published container — CI tests assert that no code path can update or remove one.
- **State determines visibility**, and that check composes with tenant isolation (§5.6) and RBAC (§5.5). All three must pass. Cross-team WIP leakage is treated with the same severity as a cross-tenant leak.
- Supersession is explicit and traceable: every container knows what it superseded and what superseded it, so the full lineage is reconstructible.

#### 6.7.2 Information containers, naming and metadata
- **Container naming is configurable per project**, not hardcoded. Delimiter-separated fields following the ISO 19650-2 pattern (project, originator, volume/system, level/location, type, role, number), validated on upload against per-project allowed-value lists, with a clear error naming the offending field.
- **Suitability/status codes and revision codes are tenant- and project-populated, not shipped.** Organisations customise these lists, and — see §17.5 — **the standard's own code tables are copyrighted and must not be reproduced in the product**. Ship the *mechanism* (configurable code lists with descriptions, ordering, and permitted transitions) and let each project populate it, or let the customer import their own.
- Revision identifiers follow the preliminary/contractual convention (e.g. `P01.01`, `C01`) with the scheme configurable per project.
- Metadata is retained in full for the life of the container and is versioned alongside it. Metadata changes are audited.

#### 6.7.3 Information delivery planning
Support the delivery-phase artefacts as structured, queryable entities — not attached PDFs: Exchange Information Requirements (EIR), BIM Execution Plan (BEP), Master Information Delivery Plan (MIDP) and Task Information Delivery Plans (TIDP), with delivery milestones, responsible parties, due dates, and status roll-up. Late and at-risk deliverables are visible without anyone building a spreadsheet.

#### 6.7.4 Formats and federation
- Open exchange formats are first-class: **IFC, COBie, BCF**. Preserve federated model relationships and container references so a federation is reconstructible from its parts.
- **Model files are large.** Every rule in §7.7 applies with force: stream to object storage, never load a model into heap, process extraction/thumbnailing/validation in the sandboxed isolated worker (§5.13.10), and make it asynchronous with progress reporting (§7.1). A 2 GB IFC upload must not touch application memory or block a request thread.
- Viewer/geometry processing is a candidate for the native-code exemption in §12 — but only under the profiling evidence rule, and never parsing untrusted input in-process.

#### 6.7.5 ISO 19650-5 — security-minded approach
This part is not optional for us given the Defence and government scope (§6.5, §6.6):
- Conduct a **sensitivity assessment** for the built asset and record the resulting classification against the project.
- Information about secure sites — models, geospatial data, systems layouts, asset registers — is **treated as classified material**, with need-to-know access, restricted export, watermarking where required, and access logged in the immutable audit trail.
- A security management plan and breach/incident procedure exist for sensitive projects; access by nationality or clearance is enforceable where a contract requires it (§6.6).
- Aggregation risk is real: individually innocuous containers can be sensitive in combination. Bulk export and search across a sensitive project require elevated permission and are audited as a security event.

#### 6.7.6 Archive and long-term retention
The archive state must remain genuinely immutable and retrievable **for the operational life of the asset — decades, not years**. This has concrete engineering consequences that must be planned now, not deferred:
- WORM storage with Object Lock and integrity verification on a schedule (hash chain per §5.7).
- A documented **format migration strategy** — the formats readable today may not be in twenty years — with migration events themselves audited, and the original bitstream preserved alongside any migrated derivative.
- Retention rules survive tenant offboarding where a contract requires it. This interacts with GDPR erasure (§6.2) and with crypto-shredding (§5.2): **document the conflict and its resolution per contract**, because a legal retention obligation and an erasure request can genuinely collide.


---

## 7. Performance

### 7.1 Budgets — these are gates, not aspirations
- **Every interactive request completes in under 1 second. This is a hard rule, not an average.** Target p50 < 200 ms, p95 < 500 ms, **p99 < 1000 ms**, measured as server time excluding client network.
- **Bulk operations are the only exception**, and they are exempt because they are **asynchronous by design** — not because they are allowed to be slow. A bulk operation returns `202 Accepted` with a job ID in under 1 second, processes on a queue, and reports progress. There is no third category: an endpoint either returns in under a second or it returns a job ID in under a second.
- Bulk = report generation, data export, batch import, document/model processing, bulk permission changes, mass notifications. If an operation's cost scales with a user-supplied count, it is bulk.
- **Every synchronous endpoint declares a timeout budget** and is instrumented against it. Breaching 1 s in production raises an alert with the endpoint named; three consecutive days of breach opens a ticket automatically.
- A synchronous endpoint that cannot be made to fit is redesigned — paginate it, precompute it, cache it, or make it async. "It's inherently slow" is not an accepted answer; it means the design is wrong.
- Frontend: **LCP < 2.0 s, INP < 200 ms, CLS < 0.1** on a mid-tier device over 4G. Initial JS bundle **< 250 KB gzipped**; route chunks < 100 KB.
- Database: no query > 100 ms in the normal path; none > 1 s ever.
- **CI fails on regression**: k6/Gatling load tests and Lighthouse CI run against every release candidate with thresholds enforced.

### 7.2 Reduce round trips
- Design endpoints around **screens and use cases**, not tables. One screen should generally need one request.
- Support field selection (`?fields=`) and controlled expansion (`?expand=customer,lineItems`) instead of N follow-up calls. Provide a batch endpoint for bulk operations.
- **HTTP/2 (or HTTP/3) enabled** at Apache so multiplexing removes head-of-line blocking.
- `ETag` / `If-None-Match` and `Last-Modified` for conditional GETs; long-lived immutable caching for fingerprinted static assets (`Cache-Control: public, max-age=31536000, immutable`), `no-store` for authenticated API responses.
- Client-side request coalescing, deduplication, and caching via RxJS operators (`shareReplay`, `switchMap`) over `HttpClient`. Debounce search/typeahead (≥300 ms) and cancel superseded requests.
- **Server-Sent Events or WebSockets** for live updates instead of polling. If polling is unavoidable, back it off adaptively.
- Prefetch predictable next routes and data on hover/idle.

### 7.3 Lazy loading
- **Route-level code splitting** with `loadComponent`/`loadChildren`; component-level splitting via dynamic `import()` for heavy widgets (editors, charts, viewers, PDF/3D renderers).
- Virtualise long lists and tables (Angular CDK `cdk-virtual-scroll-viewport`) — never render 10,000 DOM rows.
- Images: lazy `loading="lazy"`, correct `width`/`height` to prevent CLS, modern formats (WebP/AVIF), responsive `srcset`, and server-generated thumbnails.
- Defer non-critical work: analytics, feature flags refresh, and secondary panels load after first paint.
- Backend: JPA associations are `LAZY` by default; fetch explicitly with joins/entity graphs where needed. **N+1 queries are a build failure** — detect them in integration tests (Hypersistence Utils / datasource-proxy assertions).
- Modular monolith modules load their heavy resources on first use, not at startup.

### 7.4 Compression
- **Brotli (preferred) with gzip fallback** at Apache for HTML, JS, CSS, JSON, SVG, XML. Do not re-compress already-compressed formats (JPEG, PNG, WebP, ZIP, PDF, video).
- Compress **request** bodies too for large client uploads (`Content-Encoding: gzip`) with a decompressed-size limit to prevent zip-bomb DoS.
- Compression is applied at the edge/proxy, not in application threads.
- Static assets are pre-compressed at build time (`.br`, `.gz`) and served directly.
- Minify and tree-shake JS/CSS; strip source maps from production bundles (upload them to the error tracker instead).
- Consider a binary format (Protobuf/MessagePack) only for proven-hot internal service-to-service paths — JSON stays the public API format.

### 7.5 Database tuning
- **Index deliberately**: every foreign key, every column in a `WHERE`/`JOIN`/`ORDER BY` on a hot path, and **`tenant_id` as the leading column** of most composite indexes. Partial indexes for filtered subsets (e.g. `WHERE deleted_at IS NULL`), covering indexes to enable index-only scans, GIN for JSONB/full-text, BRIN for large append-only time-series.
- **Every new query ships with its `EXPLAIN (ANALYZE, BUFFERS)` plan in the PR description.** Sequential scans on large tables must be justified.
- Remove unused indexes — they cost write throughput and storage. Review `pg_stat_user_indexes` quarterly.
- **Connection pooling**: HikariCP sized to `((core_count * 2) + effective_spindle_count)` as a starting point — **small pools outperform large ones**. Add **PgBouncer** (transaction mode) in front for high connection counts and serverless/autoscaled workloads.
- Keep transactions short; never hold a transaction open across an HTTP call, a queue publish, or user think-time. Set `statement_timeout`, `lock_timeout`, and `idle_in_transaction_session_timeout`.
- Batch writes (`saveAll` with `hibernate.jdbc.batch_size`, `COPY` for bulk load), avoid `SELECT *`, always paginate (keyset/cursor pagination for deep pages — `OFFSET 100000` is a bug).
- Partition large tables by tenant or time where volume justifies it. Archive cold data out of hot tables.
- Read replicas for reporting and analytics; route read-only transactions there explicitly.
- Monitor `pg_stat_statements`, cache hit ratio (>99%), bloat, long-running queries, lock waits, and replication lag. Autovacuum tuned for high-churn tables.
- Migrations are versioned (Flyway/Liquibase), forward-only, **backwards-compatible for zero-downtime deploys** (expand → migrate → contract), and never lock a large table (`CREATE INDEX CONCURRENTLY`, `ALTER TABLE ... ADD COLUMN` without a volatile default on old versions).

### 7.6 Caching
Cache what is **read often and changes rarely**. Cache-aside pattern via **Valkey**.

Cache: reference/lookup data, tenant configuration and feature flags, permission and role resolution, rendered menu/navigation trees, expensive aggregate reports, session data, rate-limit counters, and idempotency records.

Rules:
- **Every cache key is tenant-namespaced**: `{env}:{tenant}:{entity}:{version}:{id}`. A tenant-agnostic key touching tenant data is a security defect.
- **Every entry has a TTL.** No unbounded caches. Layer TTLs: short (30 s–5 min) for volatile, long (hours) for reference data.
- **Explicit invalidation on write** in the same unit of work as the DB change, plus TTL as a safety net. Prefer versioned keys over deletion for hot paths.
- Do not cache PII or sensitive fields without encryption; never cache authorisation *decisions* longer than the shortest permission-change propagation window (≤60 s), and invalidate immediately on role change.
- Guard against **stampede** (request coalescing / probabilistic early expiry), **penetration** (cache negative results briefly), and **avalanche** (jitter the TTLs).
- Application-local caches (Caffeine) for ultra-hot, small, tenant-agnostic data only — remember they are per-instance and cannot be invalidated cluster-wide reliably.
- HTTP caching and a CDN for static assets and public content.
- **Cache hit ratio is a monitored metric with an alert.** A cache that isn't hitting is pure added latency and complexity — remove it.

### 7.7 Streaming, never load whole files into memory
- **Never** call `getBytes()`, `readAllBytes()`, `Files.readAllBytes()`, or `toByteArray()` on user-supplied content. **Never** hold a full upload or export in a `byte[]` or `String`.
- Uploads: stream from the request `InputStream` straight to object storage using multipart/chunked upload. Enforce size limits during the stream, not after.
- Downloads: `StreamingResponseBody` / `InputStreamResource` with a bounded buffer (8–64 KB); set `Content-Length` where known and support HTTP Range requests for large media and resumable downloads.
- Exports: stream CSV/JSON rows as they are produced. Use a Postgres **cursor/`Stream<T>` with a fetch size** inside a read-only transaction — never materialise a million-row list. Flush periodically and clear the persistence context.
- Parsing: use event/streaming parsers — SAX/StAX for XML (with XXE disabled), Jackson streaming API for large JSON, SXSSF for Excel writes and streaming readers for Excel reads, PDFBox with incremental access.
- Large in-flight work uses bounded temp files with guaranteed cleanup (try-with-resources), on an encrypted volume, with a disk-space guard — never unbounded heap.
- Set explicit JVM heap limits matched to container limits; a memory-leak or OOM regression test runs in the nightly pipeline.
- Apply back-pressure end to end (Reactor/`Flow`) so a slow consumer cannot exhaust producer memory.

### 7.8 Asynchronous processing and messaging

**Decision: start with ActiveMQ Artemis alone. Do not deploy Kafka until a documented trigger fires.**

Rationale: our workload profile is **transactional work queues**, not event streams. Document processing, virus scanning, model extraction, notifications, exports, imports, and webhook delivery are all "do this unit of work reliably, once, with retries and a dead-letter queue". Artemis does that well, supports scheduled and delayed delivery, message groups, priorities, and last-value queues, speaks JMS/AMQP/MQTT/STOMP, and is roughly one component to operate. Kafka is a distributed log optimised for a different problem — high-throughput ordered streams consumed independently and replayed — and brings partition planning, consumer group rebalancing, retention tuning, and schema registry operations with it. **Running both doubles the operational surface, the failure modes, the on-call knowledge, and the cost, and until a trigger below fires it buys nothing.**

**Use Artemis for** (default for everything): document and IFC/model processing pipelines, virus scanning (§5.13), thumbnail and derivative generation, report and export generation, bulk import, notification and email dispatch, webhook delivery with retry, scheduled and delayed jobs, bulk permission changes, AI enrichment calls (§10).

**Adopt Kafka only when one of these is demonstrably true** — record which one in an ADR:
1. **Multiple independent consumers need the same event stream** and reprocessing one must not affect the others (e.g. audit fan-out simultaneously feeding SIEM, analytics, and a search indexer).
2. **Replay is a functional requirement** — rebuilding a projection, a search index, or an analytics store from history, rather than an operational convenience.
3. **Sustained throughput exceeds what Artemis comfortably handles** for our message sizes, proven by load testing rather than estimated.
4. **Event sourcing** is chosen as the persistence model for a bounded context (which needs its own ADR first).
5. **Ordered, partitioned processing at scale** where per-key ordering must be guaranteed across many consumers.

Given ISO 19650 is in scope (§6.7), trigger 1 or 2 is the most likely to fire — CDE state transitions and audit events are a natural stream with several downstream consumers. **Design for that now without deploying it**: publish through the transactional outbox behind a `DomainEventPublisher` interface, so moving a topic from Artemis to Kafka is an adapter change, not a rewrite of every producer and consumer.

**Rules that apply whichever broker is in use:**
- **Transactional outbox pattern** for publishing — never write to the database and the broker in two uncoordinated steps.
- **Consumers are idempotent** (dedupe on message key or idempotency ID) because delivery is at-least-once. Design for reprocessing.
- Every consumer has bounded retries with exponential backoff and jitter, a **dead-letter queue with alerting**, a poison-message strategy, and a documented replay procedure.
- Messages carry `tenantId`, `correlationId`, `traceId`, and a schema version. Schemas evolve backwards-compatibly.
- **Never put file contents or PII in a message** — put the storage reference and the tenant-scoped ID.
- Job status is queryable by the user with progress, and jobs are cancellable.
- Partition or group by tenant to prevent one large tenant starving others; apply per-tenant concurrency caps (noisy-neighbour protection).
- Route to async whenever work exceeds ~1 s, touches many records, calls a slow third party, or is retry-worthy (§7.1).

---

## 8. Scalability, resilience and monitoring

### 8.1 Stateless and horizontally scalable
- **Application instances hold no session state.** Sessions live in the distributed cache; files in object storage; scheduled work coordinated via a distributed lock (ShedLock) or a leader election — never "the one node with the cron on it".
- Any instance can serve any request; any instance can be killed at any time without data loss (test this — chaos/pod-kill drills).
- Startup is fast and self-configuring: no manual step between "container starts" and "instance serves traffic".

### 8.2 Load balancing and resilience
- Load balancer/ingress in front of Apache; Apache reverse-proxies to the application pool. Health-check-driven membership, connection draining on shutdown, and **graceful shutdown** (stop accepting, finish in-flight, deregister) with a termination grace period longer than the longest request.
- **Multi-AZ deployment minimum**; multi-region for tenants whose SLA requires it (within the residency boundary, §6.1).
- Resilience patterns via Resilience4j on every outbound dependency: **timeouts (always), retries with exponential backoff + jitter (only for idempotent operations), circuit breakers, bulkheads, and rate limiters.**
- **Graceful degradation**: if the cache is down, serve from the database (slower, still correct). If search is down, fall back to basic filtering. If the AI service is down, hide the AI feature — never fail the whole page. Every dependency has a documented fallback.
- Zero-downtime deployments: rolling or blue/green, backwards-compatible migrations (§7.5), feature flags to decouple deploy from release, automated rollback on error-rate/latency breach.

### 8.3 Health and status
- **Spring Boot Actuator** with distinct probes:
  - `/actuator/health/liveness` — is the process healthy? (restart if not)
  - `/actuator/health/readiness` — can it serve traffic? (dependency checks: DB, cache, broker, storage, IdP)
  - `/actuator/health` — detailed, **internal network only**
  - `/actuator/prometheus` — metrics scrape, internal only
- **Public status/health page** showing per-component status, current incidents, scheduled maintenance, and historical uptime. Tenant admins additionally see their own integration health (SSO certificate expiry, webhook delivery failures, storage connectivity, queue backlogs).
- Readiness must fail if a *required* dependency is down, and must **not** fail for an optional one — otherwise a degraded cache takes the whole fleet out of the load balancer.
- Synthetic monitoring runs the critical user journeys (login, SSO login, core create/read, upload) from multiple regions continuously.

### 8.4 Autoscaling
- **Horizontal Pod Autoscaler** (or cloud-native equivalent) on CPU **and** custom metrics — request rate, p95 latency, and **queue depth** (KEDA) for workers, which is the metric that actually matters for batch load.
- Scale-out fast, scale-in slow (stabilisation window) to avoid thrash. Minimum replicas ≥ 2 always (no single point of failure). Maximum replicas capped and alerted on, so a runaway loop doesn't produce a runaway bill.
- Pod Disruption Budgets, resource `requests`/`limits` set from measured usage, and JVM configured to respect container memory (`-XX:MaxRAMPercentage`).
- Database is the usual scaling ceiling: read replicas, PgBouncer, and connection budgets sized so autoscaled app pods cannot exhaust Postgres connections. **Set `max_connections` awareness in the autoscaling policy.**
- Cost guardrails: per-tenant quotas and rate limits, and cost-per-tenant metrics so unprofitable usage patterns are visible.

### 8.5 Observability
- **OpenTelemetry** end to end: distributed traces with `traceId` propagated from the browser through Apache, application, queues, and workers; the `traceId` is surfaced in UI error messages and in every log line.
- **Metrics** (Micrometer → Prometheus): RED (rate, errors, duration) per endpoint; USE (utilisation, saturation, errors) per resource; plus business metrics (signups, uploads, jobs processed, AI calls and token spend) — dimensioned by tenant where cardinality permits.
- **Structured JSON logs** with consistent fields (`timestamp`, `level`, `service`, `traceId`, `tenantId`, `userId`, `event`), no PII, shipped centrally with retention per §5.7.
- **SLOs with error budgets** defined per critical journey, alerting on burn rate — not on every CPU spike. Every alert is actionable and has a linked runbook; alerts that fire without action get deleted.
- Profiling available in production (async-profiler / JFR) with low overhead for diagnosing real incidents.

---

## 9. Deployment: containers, clouds, CI/CD

### 9.1 Docker
- **Multi-stage builds**; runtime image contains only the JRE/runtime and the artifact. Base on a minimal, actively maintained image (distroless, `-slim`, or a minimal UBI). No `latest` tags — pin by digest.
- **Run as a non-root user** with a read-only root filesystem, dropped capabilities, and `no-new-privileges`. Writable mounts only for explicit temp paths.
- **No secrets in images, build args, or layers.** Secrets are injected at runtime from the platform secret store.
- Reproducible builds, deterministic dependency resolution (lockfiles), layer ordering optimised for cache hits.
- One process per container; correct signal handling for graceful shutdown; `HEALTHCHECK` defined.
- Images are scanned (§5.10), signed, and accompanied by an SBOM. `.dockerignore` excludes source, tests, and local config.

### 9.2 Jenkins pipeline
Declarative `Jenkinsfile`, versioned with the code, shared libraries for common stages. Required stages:

1. **Checkout & setup** — pinned tool versions, dependency cache restore
2. **Static analysis** — Spotless/Checkstyle, SpotBugs, PMD, ESLint, `tsc --noEmit`, ArchUnit
3. **Secret scan** — Gitleaks, full history on `main`
4. **Build** — Gradle + Angular CLI production build
5. **Unit tests** — JUnit 5 + Vitest, **fail under coverage thresholds (§14)**
6. **Integration tests** — Testcontainers (Postgres, Artemis, Valkey, ClamAV, Keycloak, MinIO)
7. **Cross-tenant isolation suite** — required gate (§5.6)
8. **SAST** — Semgrep + find-sec-bugs, fail on High
9. **SCA + licence scan** — Dependency-Check/Trivy + licence policy (§2.1), fail on Critical/High, forbidden licence, or **any licence change on an existing dependency** (§17.2)
10. **OpenAPI gate** — regenerate spec, fail if it differs from the committed `/api/openapi.yaml`; Spectral lint; `oasdiff` breaking-change check; schema validation of all integration-test responses; Schemathesis fuzz run (§3.5)
11. **Attribution + SBOM** — `THIRD-PARTY-NOTICES.txt` regenerated and diffed; CycloneDX SBOM with SPDX licence IDs generated and archived (§17.2)
12. **Container build + scan + sign** — Trivy/Grype, cosign signature and attestation
13. **Deploy to staging** — automated, with migrations
14. **DAST** — OWASP ZAP authenticated scan against staging
15. **Performance tests** — k6/Gatling with budget assertions (§7.1); Lighthouse CI for the frontend
16. **Accessibility gate** — axe-core via Playwright across critical journeys; fails on any serious or critical violation; Lighthouse accessibility budget enforced (§1A.5)
17. **E2E tests** — Playwright across supported browsers
18. **Compliance evidence export** — test results, scan reports, approvals archived for SOC 2/ISO/IRAP
19. **Promote to production** — manual approval gate, blue/green or rolling, automated smoke tests, automated rollback on SLO breach

Rules: pipeline stages are idempotent and re-runnable; build once, promote the same artifact through environments (never rebuild per environment); environment differences come from config and secrets only; credentials come from the Jenkins credential store or a vault, never from the repo; the pipeline itself is code-reviewed.

### 9.3 Cloud-agnostic and on-premises deployment
The application must deploy unchanged to **Azure, AWS, GCP, and on-premises/air-gapped**.

- **No cloud-vendor SDK in application code.** Cloud services are consumed through our own interfaces with per-provider adapters in `infrastructure`, or through open protocols/standards.
- Portable choices: Kubernetes (or Docker Compose for small on-prem), PostgreSQL (managed or self-hosted), S3-compatible object storage API, Artemis, Keycloak, Prometheus/OTel. Managed equivalents are used via the same interface.
- Infrastructure as Code with **Terraform/OpenTofu** — one module set, provider-specific implementations, no click-ops. Helm charts for application deployment.
- Configuration strictly via environment variables / mounted config (12-factor). **The same image runs everywhere.**
- Secrets abstraction supports Azure Key Vault, AWS Secrets Manager, GCP Secret Manager, and HashiCorp Vault / Kubernetes Secrets on-prem.
- **On-prem and air-gapped mode is a first-class deliverable**: internal artifact mirrors, offline licence activation, local Pwned Passwords dataset (§4.3), local AI inference or AI features disabled (§10), local mail relay, no telemetry egress, documented bandwidth-free upgrade path.
- Portability is **tested**, not claimed: CI deploys to at least two providers plus a local Compose stack.

---

## 10. AI features

### 10.1 Never send sensitive data to an LLM or third-party service
This is an absolute rule with no per-feature exceptions.

- **Never transmit**: credentials, tokens, keys; personal identifiers (names, emails, phone numbers, addresses, national IDs, dates of birth); financial or health data; anything classified (OFFICIAL-SENSITIVE, PROTECTED and above); customer documents from Defence/government tenants; security-relevant configuration, audit logs, or infrastructure detail; another tenant's data in any form.
- **Mandatory pre-flight redaction/tokenisation layer**: every outbound AI payload passes through a central `AiPayloadSanitiser` that applies allow-list field selection (not deny-list), PII detection and pseudonymisation (replace with stable placeholders, re-hydrate locally on return), and a hard reject on classified content. The sanitiser is unit-tested with an adversarial corpus and cannot be bypassed by feature code.
- **Per-tenant AI kill switch**, default **off** for Defence, government, and IRAP-scoped tenants. Enabling requires an explicit, audited admin action with the data-handling terms shown.
- **Log every AI call** (audit event: who, when, feature, model, provider, token counts, whether redaction fired) — but **never log the payload content itself**.
- Provider terms must guarantee **no training on our data**, zero/short retention, and a region that satisfies the tenant's residency requirement (§6.1). The AI provider is a **sub-processor** and appears in the register, in the DPA, and in the tenant-facing Trust Centre.
- **Local/self-hosted inference** (open-weight models on our infrastructure) is the required option for sovereign and air-gapped deployments, and is preferred generally where quality is adequate.
- Prompt-injection defence: treat all model output as untrusted input — never execute it, never interpolate it into SQL/shell/HTML, validate and constrain it, and never let it trigger a privileged action without human confirmation. Model output cannot escalate the calling user's permissions.

### 10.2 Do not call an LLM when code can do the job
**Default position: write deterministic code.** An LLM call is slower, costlier, non-deterministic, harder to test, a privacy risk, and an availability dependency. Reach for it only when the task genuinely requires open-ended language understanding.

Use code, not a model, for:
- Validation, formatting, parsing, and data transformation
- Classification with a fixed, known label set → rules, regex, or a small trained classifier
- Extraction from structured or semi-structured documents → parsers and templates
- Search and ranking → Postgres full-text / OpenSearch / vector similarity **without** a generative call
- Summarising structured records → templated generation from fields
- Calculations, aggregations, sorting, deduplication, and business rules — **always** code
- Anything with a correct answer that can be computed

Claude Code: when asked to implement an "AI feature", **first propose the deterministic implementation.** If a model is genuinely required, say specifically why code cannot do it, and propose the smallest-scope model use (e.g. model classifies once, code handles everything downstream).

### 10.3 Minimise token usage
- **Right-size the model**: the smallest model that passes the quality bar. Cascade — cheap model first, escalate to a larger one only when confidence is low or validation fails.
- **Send the minimum context**: retrieve and pass only the relevant chunks (RAG with tight top-k), never a whole document or conversation history "just in case". Truncate and summarise history; keep rolling summaries rather than full transcripts.
- **Prompt caching** for stable system prompts and shared context; **response caching** keyed by a hash of the normalised input (tenant-scoped), with TTL — identical questions must not be paid for twice.
- **Structured output** (JSON schema / tool use) to eliminate re-asks and parsing retries. Cap `max_tokens` to what's actually needed.
- **Batch** where latency allows; **stream** where the user is waiting so perceived latency drops without extra cost.
- Pre-filter with code so the model only sees candidates that survived cheap deterministic filtering; post-process with code so the model isn't asked to format, sort, or calculate.
- **Per-tenant token budgets, rate limits, and cost alerts**, with graceful degradation when exhausted (disable the feature, don't fail the page). Cost per tenant and per feature is a monitored metric reviewed monthly.
- Evaluate AI features with a regression test set (golden inputs/outputs) so prompt or model changes can be validated before release.

---

## 11. Pluggable file storage

A single interface, four implementations, selected by configuration — **no feature code knows which backend is active**.

```
StorageProvider
  ├─ store(tenantId, key, InputStream, metadata) → StorageObjectRef   // streaming, never byte[]
  ├─ retrieve(tenantId, key) → InputStream                            // streaming
  ├─ presignedUrl(tenantId, key, Duration, Operation) → URI
  ├─ delete(tenantId, key)
  ├─ exists / metadata / list(prefix)
  ├─ copy / move
  └─ initiateMultipartUpload / completeMultipartUpload
```

Implementations: **Azure Blob Storage**, **AWS S3** (and any S3-compatible store: MinIO, Ceph, Wasabi), **Google Cloud Storage**, **Local filesystem / VM volume** (for on-prem and air-gapped).

Requirements:
- Selected via `storage.provider=azure|s3|gcs|local` with provider-specific config; validated at startup with a connectivity self-test that surfaces on the readiness probe.
- **Tenant-prefixed keys**, always: `{env}/{tenantId}/{category}/{uuid}`. Path traversal is impossible by construction — keys are server-generated, never client-supplied (§5.13.6).
- Server-side encryption enabled on every backend; customer-managed keys supported (§5.2). The local provider encrypts at the filesystem/volume level and supports application-layer encryption for sensitive categories.
- Pre-signed/SAS URLs are short-lived (≤15 min), scoped to a single object and operation, and audited. The local provider implements this via a signed, time-limited token validated by the application.
- Versioning, soft delete, retention/legal hold, and lifecycle rules (hot → cool → archive) exposed uniformly where the backend supports them; the local provider implements equivalents or clearly reports the capability as unavailable.
- **The same integration test suite runs against all four providers in CI** (MinIO/Azurite/fake-gcs-server/local temp dir). A provider that fails the suite is not shipped.
- Multipart/chunked upload and resumable download supported everywhere; large-file operations never buffer fully in memory (§7.7).

---

## 12. Native code / other languages

Java + TypeScript are the defaults. Another language is permitted **only** under all of these conditions:

1. **Profiled evidence** that the hot path is genuinely CPU-bound and is a real bottleneck in production-representative load (not a micro-benchmark).
2. **JVM optimisation has been exhausted first** — algorithmic improvement, better data structures, the Vector API, virtual threads, GC tuning, caching, and doing less work. Most "we need C++" conclusions dissolve here.
3. The expected improvement is **large and measurable — at least 2×** on a path that materially affects user experience or cost.
4. The scope is a **narrow, pure, well-defined computational kernel** with a small interface (geometry/mesh processing, image or point-cloud transforms, compression, cryptographic primitives, numeric solvers) — never business logic, never anything touching auth, tenancy, or persistence.
5. An **ADR** records the benchmark, the alternatives rejected, the maintenance owner, and the exit plan.

If approved:
- Prefer the **Java Foreign Function & Memory API** over JNI for new work (safer, no bespoke JNI glue).
- Prefer **Rust** over C++ for new native code — memory safety eliminates an entire class of exploitable bugs, which matters given §5 and §6.
- The native component is **memory-safe or fuzz-tested** (libFuzzer/AFL++/cargo-fuzz), sanitizer-clean (ASan/UBSan), and has its own CI with the same vulnerability scanning as everything else.
- It must **never parse untrusted input in-process** — run it in the sandboxed worker (§5.13.10).
- Cross-compiled and tested for every supported platform; a **pure-Java fallback implementation exists and is tested**, so the product still runs where the native artifact is unavailable.

---

## 13. Configuration and secrets

- **12-factor**: all configuration from environment variables or mounted files. No environment-specific code branches, no `if (env == "prod")`.
- Configuration is **validated at startup** (`@ConfigurationProperties` + Bean Validation) and the application **fails fast and loudly** on invalid or missing required config — never boots into a half-configured state.
- Every configuration property is documented in `/docs/configuration.md` with type, default, valid range, whether it's required, and whether it's a secret.
- Secrets come from the platform secret store (§9.3), are rotatable without a code change, and are **never** logged, echoed by an API, or included in error messages or support bundles.
- Feature flags for progressive rollout, tenant-level enablement, and kill switches. Flags are cleaned up within one release of full rollout — stale flags are technical debt.

---

## 14. Testing

**Coverage gate: ≥ 90% line coverage and ≥ 85% branch coverage (JaCoCo for Java, Vitest/c8 for TypeScript). The build fails below the threshold.** Generated code, DTOs, and config classes may be excluded — nothing else. Coverage is a floor, not a goal: high coverage with weak assertions is worse than honest lower coverage, so **mutation testing (PIT) runs on core domain modules** with a tracked mutation-score threshold.

- **Unit tests**: fast (whole suite < 2 min), no Spring context, no database, no network. Test behaviour and edge cases, not implementation details. One logical assertion per test; descriptive names (`shouldRejectUploadWhenTenantQuotaExceeded`).
- **Integration tests**: **Testcontainers** for real Postgres, Artemis, Valkey, ClamAV, MinIO, and Keycloak. **No H2 or in-memory substitutes** — they hide dialect, RLS, and locking bugs, and RLS is a security control we must actually test.
- **Security tests (required, non-negotiable gates)**: cross-tenant access matrix (§5.6), authorisation matrix per role per endpoint, authentication flows including SAML/OIDC negative cases (bad signature, expired assertion, replayed ID, wrong audience), injection payloads, file-upload malicious-file handling, rate-limit and lockout behaviour.
- **Contract tests** against the OpenAPI spec; consumer-driven contracts for internal service boundaries.
- **Frontend**: Vitest + Angular TestBed with Testing Library queries (test user-visible behaviour, query by role/label, never by implementation detail), MSW for API mocking, Playwright for E2E critical journeys, and visual regression on key screens.
- **Accessibility tests** (§1A.5): axe-core assertions in component and E2E tests, keyboard-navigation tests for every interactive pattern, and focus-management tests on route changes and modals. Automated coverage is a floor — it catches a minority of WCAG issues, so the manual keyboard pass and the screen-reader matrix are the real gates.
- **Performance and load tests** in the pipeline with enforced budgets (§7.1), including a soak test for memory leaks and a spike test for autoscaling behaviour.
- **DR test**: automated monthly restore verification (§5.8).
- Test data is synthetic — **never production data**, never real personal data, in any environment.
- Flaky tests are quarantined and fixed within one sprint, not re-run until green.

---

## 15. Documentation

Maintained in-repo, reviewed as part of the PR that changes the behaviour:
- `/README.md` — quick start; a new developer is productive in < 30 minutes
- `/docs/adr/` — architecture decision records
- `/docs/configuration.md` — every setting
- `/docs/runbooks/` — one per alert and per operational procedure
- `/docs/compliance/` — control matrix, ROPA, obligations register, SSP, DR plan
- `/docs/accessibility/` — accessibility statement, current VPAT/ACR, audit reports, screen-reader test matrix (§1A.6)
- `/docs/competitive-analysis.md` — UX and feature benchmarking (§1.3)
- `/docs/licences.md` — approved licences and exceptions
- `/api/openapi.yaml` — generated, committed, and linted spec (§3.5); interactive docs published at `/api/docs`
- `/THIRD-PARTY-NOTICES.txt` — generated attribution file, shipped with every distribution (§17.2)
- User-facing help and API docs versioned with releases

Code comments explain **why**, never **what**. Public APIs and non-obvious domain rules get Javadoc/TSDoc. If a comment is needed to explain what code does, rename things until it isn't.

---

## 16. Definition of Done

A change is complete only when **all** of the following are true:

- [ ] Meets the requirement, including the edge cases and error paths
- [ ] Follows §3 naming, structure, and complexity limits; no new duplication beyond the rule of three
- [ ] Server-side validation on every input; authorisation enforced server-side with an explicit permission
- [ ] Tenant isolation preserved — RLS-covered, tenant-scoped cache keys, queue headers, and storage prefixes
- [ ] **CDE integrity preserved** where the change touches information containers: state transitions go through the state machine, published revisions are never edited or deleted, supersession lineage is intact, and visibility composes correctly with tenancy and RBAC (§6.7)
- [ ] Unit + integration tests written; coverage thresholds met; cross-tenant test added for any new resource
- [ ] Security checklist: no injection surface, no secrets, no sensitive data in logs, no new PII to third parties, OWASP items in §5.12 considered
- [ ] Audit events emitted for security-relevant actions
- [ ] Performance budgets met; `EXPLAIN ANALYZE` attached for new queries; no N+1; no full-file-in-memory
- [ ] **OpenAPI spec regenerated and committed in the same change**; Spectral lint clean, no unintended breaking change, examples and schema constraints match the DTO validation (§3.5)
- [ ] No new dependency without approval; no deprecated/EOL/forbidden-licence component; versions verified via Context7
- [ ] **Legal clean**: no copied or ported code, AI-generated code reviewed for provenance, attribution file regenerated if dependencies changed, no third-party trademark or unearned certification claim (§17)
- [ ] **Accessibility (WCAG 2.2 AA)**: developer has done a keyboard-only pass on the changed screen; axe reports zero serious/critical violations; new charts have a data-table equivalent; new exports are tagged/PDF-UA; ACR updated if a conformance claim changed (§1A)
- [ ] Works on all four storage providers and is cloud-agnostic
- [ ] Configuration documented with a safe default; migration is backwards-compatible and zero-downtime
- [ ] Observability: metrics, structured logs with `traceId`, and an alert + runbook if it can page someone
- [ ] Compliance impact considered (residency, retention, data-subject rights, classification)
- [ ] Docs/ADR updated; feature flag added if the rollout warrants one
- [ ] CI green including SAST, SCA, licence, container scan, DAST, and performance gates

---

## 17. Legal — copyright, licensing, patents, trademarks

**The product must ship free of copyright, licence, patent, and trademark exposure. A legal defect is a release blocker with the same severity as a Critical vulnerability.** Enterprise, government, and Defence procurement will audit this — expect to hand over an SBOM, an attribution file, and a licence report.

### 17.1 Copyright — code provenance
- **Never copy code from Stack Overflow, blog posts, tutorials, GitHub Gists, forums, or another product's source into this repository.** Stack Overflow content is CC BY-SA (a copyleft-style attribution licence, incompatible with proprietary distribution without attribution and share-alike considerations). Read it, understand it, then write your own implementation.
- **Never port, transcribe, or "translate" code from an AGPL/SSPL/GPL project** into our codebase. Reimplementing from a licensed source is a derivative work; changing the variable names does not change that.
- **Never copy from a former employer's or a competitor's codebase**, and never accept a contribution from someone who may have. New joiners are briefed on this in onboarding.
- **AI-generated code (including Claude's) is reviewed for provenance.** If an output looks like a verbatim reproduction of a recognisable library, algorithm implementation, or licensed snippet, do not use it — flag it. Prefer generating from the specification rather than asking for a known project's implementation.
- Every source file carries the standard project copyright header. `LICENSE` and `NOTICE` files live at the repo root.
- **Contributor agreements**: DCO sign-off (`git commit -s`) required on every commit; a CLA for external contributors. Enforced by a CI check.
- Content assets are covered too: **fonts, icons, images, illustrations, sample data, documentation text, and CSS frameworks all have licences.** Use only assets with a documented, permissive licence (SIL OFL fonts, MIT/Apache icon sets, CC0 imagery) and record each in `/docs/licences.md`. No stock imagery without a purchased licence covering commercial SaaS distribution.

### 17.2 Licence compliance
- The allow/deny policy in §2.1 and the rejected-component table in §2.2 are binding, including for **transitive** dependencies. The CI licence scan is a hard gate.
- **Generate and ship an attribution file.** Every third-party component's licence text, copyright notice, and any required NOTICE content is aggregated into `THIRD-PARTY-NOTICES.txt`, included in the distribution, and surfaced in the product UI (Help → Open Source Licences). Apache-2.0 §4(d) and the BSD/MIT attribution clauses **require** this — omitting it is an actual licence breach, not a formality.
- **Weak-copyleft components (MPL-2.0, EPL-2.0, CDDL, LGPL)** are permitted with care: keep them as unmodified, separately-linked artifacts; if you modify one, the modified files must be released under that licence. Track every such component in `/docs/licences.md` with a note on whether it has been modified. **Default position: do not modify them** — fork-and-patch creates an obligation, so contribute upstream or work around instead.
- **GPL tools invoked out-of-process** (ClamAV, some CLI utilities) are acceptable and must stay out-of-process — never link, never embed, never bundle them into the application artifact in a way that creates a combined work.
- **Licence changes are a supply-chain risk.** Several major projects have re-licensed away from open source since 2021 (§2.2). The CI licence scan compares against the previously recorded licence per dependency and **fails on any change**, so a re-licence is caught at the upgrade PR rather than at an audit.
- **Docker base images and OS packages carry licences too.** Scan the image layers, not just the application dependencies (Syft/Trivy produce both).
- The **SBOM (CycloneDX, §5.10)** includes resolved licence identifiers (SPDX) for every component and is published per release.

### 17.3 Patents
- **Prefer Apache-2.0 over MIT/BSD where both are available.** Apache-2.0 contains an express patent grant with a retaliation clause; MIT and BSD grant copyright only and are **silent on patents**, leaving residual exposure. This is a real differentiator for enterprise and government procurement.
- **Patent-encumbered formats and algorithms are the main practical risk.** Before implementing or bundling any of the following, check current patent status and licensing terms: video and image codecs (**H.264/AVC, H.265/HEVC, AAC — these are pool-licensed and require royalties for commercial distribution; use AV1, VP9, Opus, WebP, JPEG, PNG instead**), some 3D/geometry and compression algorithms, certain barcode symbologies, and some cryptographic constructions. Do **not** bundle a codec into the product without a written licence position.
- Do not conduct freedom-to-operate analysis in engineering. **Do not read patents speculatively** — in some jurisdictions knowledge of a patent can increase damages. Route any concern to legal counsel rather than investigating independently.
- If a feature request describes a mechanism that sounds like a competitor's marketed, distinctive capability, **flag it to product and legal before building.**
- Our own contributions to upstream open source follow the project's CLA/DCO; check with legal before contributing anything close to our own patentable work.

### 17.4 Trademarks and branding
- **Never use a third party's name, logo, or icon** in the UI, marketing, or documentation without checking their trademark guidelines. Integration logos (Microsoft, Google, Salesforce, AWS, Azure) each have published brand rules governing size, colour, spacing, and permitted phrasing.
- Use nominative fair-use phrasing — "Works with Microsoft Entra ID", never "Microsoft-approved" or "Powered by Microsoft" — and avoid any implication of endorsement, partnership, or certification we do not hold.
- Do not use "SOC 2", "ISO 27001", "IRAP", "Cyber Essentials", or any certification mark in product or marketing until the certification is actually held, and then only per the issuing body's mark usage rules. **Claiming an uncertified compliance status is a misrepresentation with regulatory consequences**, not a marketing stretch.
- Our own product name, logo, and domains are trademark-cleared and registered in every jurisdiction where we sell before public launch. Check availability before naming a new module or feature publicly.
- Do not name internal classes, packages, or repos after third-party trademarks.

### 17.5 Data, content and export
- **Training data, reference datasets, and geodata carry licences.** Postcode files, address databases, map tiles, classification schemas, standards documents, and industry code lists are frequently proprietary or restrictively licensed. Verify before ingesting — this catches people out constantly in construction and government domains.
- **Standards documents (ISO, BSI, AS/NZS) are copyrighted.** We may implement a standard's requirements; we may **not** reproduce its text, tables, or code lists in the product or documentation without a licence. Paraphrase requirements, and make configurable code lists tenant-populated rather than shipping the standard's own list where that list is copyrighted (relevant to §6.7 ISO 19650 suitability codes).
- **AI provider terms**: confirm output ownership, that our data is not used for training, and the indemnity position, before any provider goes into production (§10.1).
- **Export control**: cryptographic software is export-controlled in several jurisdictions (US EAR, Australian DTCA, UK). Defence-related technical data may not be transferable to foreign nationals or offshore infrastructure (§6.6). Confirm the classification and any licence requirement before shipping to a new jurisdiction.
- **Customer data ownership is unambiguous in contract**: the customer owns their data; we hold a limited licence to process it to deliver the service. Never use customer data for product development, benchmarking, demos, testing, or model training without explicit written permission.

### 17.6 Engineering checklist
Before any release:
- [ ] Licence scan clean; no forbidden or newly re-licensed component
- [ ] `THIRD-PARTY-NOTICES.txt` regenerated, complete, and shipped
- [ ] SBOM published with SPDX licence identifiers, application **and** base image
- [ ] All fonts, icons, and images traced to a documented licence
- [ ] No patent-encumbered codec or algorithm bundled without a written position
- [ ] No third-party trademark used outside its brand guidelines; no unearned certification claim
- [ ] DCO sign-off present on every commit
- [ ] No copied code from external sources; AI-generated code reviewed for provenance

---

## 18. Open items to resolve with the team

### Settled — implement as written, do not relitigate
- **Hashing: SHA-256.** Passwords use `PBKDF2-HMAC-SHA-256` at ≥600k iterations (FIPS/ISM-approved and SHA-256 based); Argon2id available as a non-FIPS option. Bare single-round SHA-256 of a password is a defect (§4.1).
- **Cache: Valkey** (BSD-3-Clause). Couchbase and Redis are rejected on licence grounds (§2.2).
- **Response time: under 1 second for every interactive request.** Bulk operations are async and return a job ID in under a second (§7.1).
- **Password expiry: mandatory**, interval configurable 30/60/90/180/365 days, default 90 (§4.2).
- **Observability: Prometheus + Perses.** Grafana is AGPL and excluded (§2.2).
- **OpenAPI 3.1 covering all core functionality**, CI-enforced (§3.5).
- **ISO 19650 is in scope.** The Common Data Environment is core architecture, built first, in `platform-cde` (§3.1, §6.7). Its code lists are configurable and tenant-populated, never shipped, because the standard's tables are copyrighted (§17.5).
- **Messaging: ActiveMQ Artemis only.** Kafka is adopted only when one of the five documented triggers fires, recorded in an ADR. Publish behind `DomainEventPublisher` + transactional outbox so the switch is an adapter change (§7.8).
- **Password expiry: 90 days default**, tenant-admin configurable within a deployment-level ceiling that government and Defence tiers can lock down (§4.2).

### Still open — Claude Code should surface these rather than silently choosing
1. **Classification level required per contract** — OFFICIAL-SENSITIVE vs PROTECTED drives IRAP scope, personnel clearances, FIPS-validated crypto module requirements, and whether external services (HIBP, LLM providers) are permitted at all (§4.3, §6.4–6.6, §10). **This is now the highest-value open question**, because ISO 19650-5 sensitivity assessment (§6.7.5) and the Defence access-by-clearance model depend on it.
2. **Legal review ownership** — §17 sets engineering policy, but freedom-to-operate, trademark clearance, export classification, and AI provider indemnity positions require actual counsel. Name the responsible reviewer and the point in the release process where sign-off happens.
3. **Retention versus erasure conflict** — ISO 19650 archive retention runs for the asset's operational life (decades), while GDPR Art. 17 erasure and tenant offboarding crypto-shredding pull the other way (§6.7.6). This is a genuine legal collision, not an engineering one; it needs a documented per-contract resolution before the archive model is finalised.
4. **Compliance obligations change.** IRAP/ISM, the Australian Privacy Act reform tranches, Def Stan 05-138 profiles, SOC 2 criteria, and open-source licences themselves all evolve. Verify current requirements against the source authorities before relying on this document — do not treat §6 or §17 as authoritative on their own.
