# 7. Generate the OpenAPI spec from the code, commit it, and gate on drift

- **Status:** Accepted
- **Date:** 2026-08-27 (recording a decision taken earlier)

## Context

The API is the product for anyone embedding it, and the mobile SDKs and the
Angular client are both consumers. A specification that disagrees with the
implementation is worse than none: it makes confident promises that are
wrong, and the people it misleads are integrators who cannot see the code.

## Options

**Design-first: hand-write the spec, generate server stubs.** Best when the
contract is negotiated before implementation. The spec is authoritative by
construction. But hand-written specs drift the moment someone changes a
controller without opening the YAML, and nothing detects it.

**Code-first: generate the spec from annotations.** The spec cannot drift,
because it is derived. The risk is the reverse — an under-annotated
controller produces a technically-accurate but useless spec.

**Both, reconciled in CI.** More moving parts than the problem needs here.

## Decision

Code-first with springdoc-openapi, producing OpenAPI 3.1, **committed** to
`/api/openapi.yaml`, with four gates:

1. **Drift** — CI regenerates the spec and fails if it differs from the
   committed file. An API change without a spec change cannot merge.
2. **Lint** — a Spectral ruleset encodes the coverage checklist: every
   operation needs a summary, description, stable camelCase `operationId`,
   named reusable schemas, every response code it can return referencing the
   shared `ProblemDetail`, a declared security requirement, and realistic
   synthetic examples.
3. **Breaking change** — `oasdiff` against the released spec for the same
   major version; a breaking change inside a stable version fails.
4. **Contract** — integration-test responses validated against their schemas
   at runtime, so drift fails tests and not merely linting.

The spec is never hand-edited. Annotations and DTOs are the source; the YAML
is output.

## Consequences

- Every API change shows up in review as a spec diff, which is where an
  accidental breaking change is cheapest to catch.
- Under-annotation is caught by the Spectral gate rather than shipping as a
  thin spec.
- The TypeScript client for the Angular frontend is generated from the spec,
  so the frontend structurally cannot call an endpoint that is not specified.
- Examples must be synthetic. Real names, emails or tenant identifiers in an
  example are a privacy incident in a public artefact, and the spec is
  published.
- Regenerating the spec is part of the same commit as the endpoint change,
  not a follow-up. A separate "update the spec" commit means the gate was
  bypassed on the first one.
- Swagger UI's "try it" console is disabled in production. The spec endpoint
  is public; an authenticated request console on production is not a
  documentation feature.
