# 4. Store passwords with PBKDF2-HMAC-SHA-256, with Argon2id as an option

- **Status:** Accepted
- **Date:** 2026-08-27 (recording a decision taken earlier)

## Context

Passwords were stored with BCrypt. BCrypt is a perfectly respectable choice
in general, and this decision is not a criticism of it — it is about which
approved-algorithm lists this product needs to appear on.

The target markets include Australian Government and Defence (IRAP, ISM) and
UK MOD. Contracts in that space frequently require a FIPS 140-2 or 140-3
validated cryptographic module, and the ASD Information Security Manual
publishes an approved-algorithm list. BCrypt (Blowfish-derived) is on
neither. That is a procurement blocker, not a security weakness.

## Options

**Keep BCrypt.** Cryptographically fine. Blocks the government and Defence
tiers, which is most of the reason this product exists.

**Argon2id.** The current best-practice recommendation, memory-hard, so it
resists GPU and ASIC attack far better than any iteration-count-based KDF.
Not FIPS-validated, so it does not solve the procurement problem on its own.

**PBKDF2-HMAC-SHA-256.** SHA-2 based, FIPS-validated, on the ASD approved
list. Weaker against GPU attack than Argon2id at equivalent cost, because
iteration count alone does not impose a memory cost on the attacker.

## Decision

`PBKDF2-HMAC-SHA-256` as the default: minimum 600,000 iterations, 128-bit
random per-user salt, 256-bit derived key, tuned to roughly 500 ms on
production hardware and re-baselined annually.

`Argon2id` is available per deployment via
`security.password.kdf=pbkdf2-sha256|argon2id`, using OWASP's baseline of
≥19 MiB memory, ≥2 iterations, parallelism 1 — for tenants not bound by FIPS,
where it is the better algorithm.

Both run simultaneously through Spring Security's `DelegatingPasswordEncoder`.
The algorithm identifier and every parameter are stored alongside the hash,
so parameters can evolve, and a password is transparently re-hashed on the
next successful login when the configured parameters change. No mass reset,
ever.

## Consequences

- The government and Defence tiers become reachable. That is the entire
  point of the choice.
- We accept weaker GPU resistance than Argon2id would give, in exchange for
  FIPS validation. The 600,000-iteration floor is what makes that acceptable,
  and it must never be reduced.
- **A bare single-round SHA-256 of a password is a defect**, and the fast
  primitive that makes PBKDF2 FIPS-eligible is exactly what makes an
  unwrapped digest cheap to crack. A CI grep rule enforces this rather than
  relying on review, because the failure is invisible in a diff that looks
  reasonable.
- Comparison is constant-time via `MessageDigest.isEqual`, never
  `String.equals`.
- The iteration count is a latency cost on every login. At ~500 ms it is
  deliberately the slowest thing in the authentication path, and the
  throttling in ADR 6 exists partly so that cost cannot be weaponised.
