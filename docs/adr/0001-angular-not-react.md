# 1. Keep Angular rather than rewriting the frontend in React

- **Status:** Accepted
- **Date:** 2026-08-27 (recording a decision taken earlier)
- **Supersedes:** nothing

## Context

The engineering guidelines adopted as `CLAUDE.md` specify React + Vite for
the frontend. The application that existed when those guidelines were
adopted is Angular, with a working PDF and CAD viewer, a markup layer, and a
test suite covering both.

So the guidelines and the code disagreed, and one of them had to give.

## Options

**Rewrite in React to match the guidelines.** Consistent with the written
standard. Costs months, delivers no user-visible improvement, and discards
verified code — the viewer and markup layer are the hardest and most
thoroughly tested parts of the product, and they would be rebuilt from
scratch with new bugs.

**Keep Angular and amend the guidelines.** Requires admitting the standard
is wrong for this codebase, in writing, where anyone reading it will see the
deviation and its reason.

**Keep Angular and stay quiet about the conflict.** Leaves a document that
contradicts the code, which erodes the authority of every other rule in it.

## Decision

Keep Angular. Amend `CLAUDE.md` with a note at the top recording the
deviation and its reason, rather than leaving a silent contradiction.

Every other frontend rule applies unchanged and has been translated rather
than dropped: accessibility, performance budgets, lazy loading, the
generated API client, strict TypeScript, and no business logic in
components. Only the framework-specific lines moved.

## Consequences

- The guidelines' own §0.6 — prefer deleting code to adding it, prefer the
  boring solution — is what settles this. A rewrite is the largest possible
  addition of code for the smallest possible change in behaviour.
- Anyone reading `CLAUDE.md` sees the deviation and why, so the document
  stays trustworthy on everything else.
- Framework-specific guidance elsewhere in the document must be read as
  intent rather than literal instruction. Where a rule names a React idiom,
  the Angular equivalent applies.
- If the product ever needs a genuine frontend rewrite for other reasons,
  this ADR should be revisited rather than assumed still binding.
