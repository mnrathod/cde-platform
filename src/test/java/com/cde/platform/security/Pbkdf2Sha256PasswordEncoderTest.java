package com.cde.platform.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The iteration count is deliberately low here. These tests are about the
 * encoding format and the parameter handling, not the cost — at the configured
 * 600,000 each derivation takes roughly half a second by design, which would
 * put this class well past the whole-suite budget on its own.
 */
class Pbkdf2Sha256PasswordEncoderTest {

    private static final int TEST_ITERATIONS = 1_000;
    private static final String PASSWORD = "correct horse battery staple";

    private final Pbkdf2Sha256PasswordEncoder encoder =
        new Pbkdf2Sha256PasswordEncoder(TEST_ITERATIONS);

    @Nested
    @DisplayName("encoding")
    class Encoding {

        @Test
        void writesIterationsSaltAndHashInTheDocumentedForm() {
            String encoded = encoder.encode(PASSWORD);

            // $iterations$salt$hash — the leading '$' means four fields.
            assertThat(encoded.split("\\$")).hasSize(4);
            assertThat(encoded).startsWith("$" + TEST_ITERATIONS + "$");
        }

        @Test
        void neverContainsThePasswordItself() {
            assertThat(encoder.encode(PASSWORD)).doesNotContain(PASSWORD);
        }

        @Test
        void producesADifferentHashEachTimeForTheSamePassword() {
            // A per-user salt is what stops one rainbow table covering every
            // account that chose the same password.
            assertThat(encoder.encode(PASSWORD)).isNotEqualTo(encoder.encode(PASSWORD));
        }

        @Test
        void rejectsANonPositiveIterationCountAtConstruction() {
            assertThatThrownBy(() -> new Pbkdf2Sha256PasswordEncoder(0))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("verification")
    class Verification {

        @Test
        void acceptsTheCorrectPassword() {
            assertThat(encoder.matches(PASSWORD, encoder.encode(PASSWORD))).isTrue();
        }

        @Test
        void rejectsAWrongPassword() {
            assertThat(encoder.matches("not the password", encoder.encode(PASSWORD))).isFalse();
        }

        @Test
        void rejectsAPasswordDifferingOnlyInCase() {
            assertThat(encoder.matches(PASSWORD.toUpperCase(), encoder.encode(PASSWORD))).isFalse();
        }

        @Test
        void handlesUnicodeAndLongPasswords() {
            // §4.2 forbids truncation and allows up to 128 characters.
            String awkward = "пароль-🔐-".repeat(12);
            assertThat(encoder.matches(awkward, encoder.encode(awkward))).isTrue();
        }
    }

    @Nested
    @DisplayName("cost parameters can change without locking anyone out")
    class ParameterEvolution {

        /**
         * The defect that motivates this class existing at all. Spring's
         * Pbkdf2PasswordEncoder returns false here, because it re-derives with
         * whatever count is configured now rather than the one that produced
         * the hash — so raising the cost logs out every user at once and
         * reports it as a wave of wrong passwords.
         */
        @Test
        void aHashStillVerifiesAfterTheIterationCountIsRaised() {
            String hashedAtOldCost = new Pbkdf2Sha256PasswordEncoder(TEST_ITERATIONS).encode(PASSWORD);

            var afterRebaselining = new Pbkdf2Sha256PasswordEncoder(TEST_ITERATIONS * 2);

            assertThat(afterRebaselining.matches(PASSWORD, hashedAtOldCost))
                .as("raising the cost must not invalidate stored passwords")
                .isTrue();
        }

        @Test
        void aStaleHashIsFlaggedForUpgrade() {
            String hashedAtOldCost = new Pbkdf2Sha256PasswordEncoder(TEST_ITERATIONS).encode(PASSWORD);

            assertThat(new Pbkdf2Sha256PasswordEncoder(TEST_ITERATIONS * 2)
                .upgradeEncoding(hashedAtOldCost)).isTrue();
        }

        @Test
        void aCurrentHashIsNotFlaggedForUpgrade() {
            assertThat(encoder.upgradeEncoding(encoder.encode(PASSWORD))).isFalse();
        }

        @Test
        void aStrongerHashIsLeftAlone() {
            // Re-hashing this would quietly weaken a stored credential.
            String hashedAtHigherCost =
                new Pbkdf2Sha256PasswordEncoder(TEST_ITERATIONS * 4).encode(PASSWORD);

            assertThat(encoder.upgradeEncoding(hashedAtHigherCost)).isFalse();
        }
    }

    @Nested
    @DisplayName("malformed stored values fail one login rather than the request")
    class MalformedInput {

        @ParameterizedTest
        @ValueSource(strings = {
            "",
            "not-a-hash",
            "$notanumber$c2FsdA$aGFzaA",
            "$1000$only-three-fields",
            "$1000$c2FsdA$aGFzaA$extra",
            "$0$c2FsdA$aGFzaA",
            "$-5$c2FsdA$aGFzaA",
            "$1000$!!!not-base64!!!$aGFzaA",
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
        })
        void matchesReturnsFalseWithoutThrowing(String stored) {
            assertThat(encoder.matches(PASSWORD, stored)).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "not-a-hash", "$notanumber$c2FsdA$aGFzaA"})
        void upgradeEncodingReturnsFalseWithoutThrowing(String stored) {
            assertThat(encoder.upgradeEncoding(stored)).isFalse();
        }

        @Test
        void handlesNullStoredValue() {
            assertThat(encoder.matches(PASSWORD, null)).isFalse();
            assertThat(encoder.upgradeEncoding(null)).isFalse();
        }

        @Test
        void handlesNullRawPassword() {
            assertThat(encoder.matches(null, encoder.encode(PASSWORD))).isFalse();
        }
    }
}
