package com.cde.platform.storage.local;

import com.cde.platform.storage.StorageCategory;
import com.cde.platform.storage.StorageKey;
import com.cde.platform.storage.StorageOperation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a signed download token grants, and what it refuses.
 *
 * <p>The token is a bearer credential: whoever holds it has the access. So the
 * tests worth having are the ones that try to widen it — reuse it for another
 * object, another tenant, another operation, after it expired, or with a
 * signature from a different key.
 */
@DisplayName("signed object tokens")
class SignedObjectTokenTest {

    private static final byte[] KEY =
        "a-signing-key-that-is-at-least-32-bytes-long".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER_KEY =
        "a-different-signing-key-also-32-bytes-long!!".getBytes(StandardCharsets.UTF_8);

    private final SignedObjectToken tokens = new SignedObjectToken(KEY);
    private final Instant now = Instant.parse("2026-08-27T12:00:00Z");

    private StorageKey keyFor(long tenantId, String objectId) {
        return new StorageKey("test", tenantId, StorageCategory.DOCUMENT, objectId);
    }

    private final StorageKey document = keyFor(11, "0198f4e2-3c1a-7b9d-8f21-6a5e0c4d7b33.pdf");

    private String issue(StorageKey key, StorageOperation operation, Duration validFor) {
        return tokens.issue(key, operation, now.plus(validFor));
    }

    @Nested
    @DisplayName("the happy path")
    class Valid {

        @Test
        @DisplayName("a fresh token for the right object and operation is accepted")
        void acceptsItsOwnToken() {
            String token = issue(document, StorageOperation.READ, Duration.ofMinutes(10));

            assertThat(tokens.isValidFor(token, document, StorageOperation.READ, now)).isTrue();
        }

        @Test
        @DisplayName("still valid a moment before expiry")
        void validUntilExpiry() {
            String token = issue(document, StorageOperation.READ, Duration.ofMinutes(10));

            assertThat(tokens.isValidFor(token, document, StorageOperation.READ,
                now.plus(Duration.ofMinutes(9)))).isTrue();
        }
    }

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("a token for one object does not open another")
        void boundToItsObject() {
            String token = issue(document, StorageOperation.READ, Duration.ofMinutes(10));
            StorageKey somethingElse = keyFor(11, "0198f4e2-0000-0000-0000-000000000000.pdf");

            assertThat(tokens.isValidFor(token, somethingElse, StorageOperation.READ, now))
                .isFalse();
        }

        @Test
        @DisplayName("a token for one tenant does not open another tenant's object")
        void boundToItsTenant() {
            String token = issue(document, StorageOperation.READ, Duration.ofMinutes(10));
            // Same object identifier, different tenant. If the signature covered
            // only the object id rather than the whole path, this would pass —
            // and it would be a cross-tenant read.
            StorageKey otherTenant = keyFor(12, document.objectId());

            assertThat(tokens.isValidFor(token, otherTenant, StorageOperation.READ, now))
                .isFalse();
        }

        @Test
        @DisplayName("a read token does not authorise a write")
        void boundToItsOperation() {
            String token = issue(document, StorageOperation.READ, Duration.ofMinutes(10));

            assertThat(tokens.isValidFor(token, document, StorageOperation.WRITE, now)).isFalse();
        }

        @Test
        @DisplayName("an expired token is refused even though it is correctly signed")
        void expires() {
            String token = issue(document, StorageOperation.READ, Duration.ofMinutes(10));

            assertThat(tokens.isValidFor(token, document, StorageOperation.READ,
                now.plus(Duration.ofMinutes(11)))).isFalse();
        }

        @Test
        @DisplayName("a token expiring exactly now is refused")
        void expiryIsExclusive() {
            String token = issue(document, StorageOperation.READ, Duration.ofMinutes(10));

            assertThat(tokens.isValidFor(token, document, StorageOperation.READ,
                now.plus(Duration.ofMinutes(10)))).isFalse();
        }

        @Test
        @DisplayName("a token signed with a different key is refused")
        void rejectsForeignSignature() {
            String forged = new SignedObjectToken(OTHER_KEY)
                .issue(document, StorageOperation.READ, now.plus(Duration.ofMinutes(10)));

            assertThat(tokens.isValidFor(forged, document, StorageOperation.READ, now)).isFalse();
        }

        @Test
        @DisplayName("a token with the expiry edited is refused")
        void rejectsTamperedExpiry() {
            String token = issue(document, StorageOperation.READ, Duration.ofMinutes(1));

            // Re-encode the claims with a far-future expiry, keeping the original
            // signature. This is the obvious attack on a token whose expiry the
            // holder can read.
            String claims = new String(java.util.Base64.getUrlDecoder()
                .decode(token.substring(0, token.indexOf('.'))), StandardCharsets.UTF_8);
            String extended = claims.replaceAll("\\d+;$", "99999999999;");
            String tampered = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(extended.getBytes(StandardCharsets.UTF_8))
                + token.substring(token.indexOf('.'));

            assertThat(tokens.isValidFor(tampered, document, StorageOperation.READ, now)).isFalse();
        }

        @Test
        @DisplayName("malformed input is refused rather than throwing")
        void rejectsGarbage() {
            // These arrive from the internet, so the failure mode has to be a
            // refusal rather than a 500 that reveals a stack trace.
            for (String garbage : new String[] {
                null, "", ".", "nodot", "a.b", "!!!.???", "....", "a." }) {
                assertThat(tokens.isValidFor(garbage, document, StorageOperation.READ, now))
                    .as("token %s", garbage)
                    .isFalse();
            }
        }
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("a short signing key is refused")
        void refusesShortKey() {
            // Not because HMAC-SHA-256 breaks with a short key, but because a
            // short one reliably means the value came from somewhere it should
            // not have — a config default, a truncated environment variable.
            assertThatThrownBy(() ->
                new SignedObjectToken("too-short".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
        }

        @Test
        @DisplayName("a null signing key is refused")
        void refusesNullKey() {
            assertThatThrownBy(() -> new SignedObjectToken(null))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
