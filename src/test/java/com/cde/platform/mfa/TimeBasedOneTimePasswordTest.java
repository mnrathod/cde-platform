package com.cde.platform.mfa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TOTP against the vectors published in the RFCs themselves.
 *
 * <p>This is the point of testing an algorithm implementation: the expected
 * values come from RFC 6238 Appendix B and RFC 4226 Appendix D, not from
 * running this code and recording what it said. A test that asserts the code
 * agrees with itself would pass just as happily on a subtly wrong truncation.
 */
@DisplayName("TOTP")
class TimeBasedOneTimePasswordTest {

    /**
     * The RFC 6238 SHA-1 test key: the ASCII digits "12345678901234567890".
     * Appendix B gives it in that form, so it is written that way here rather
     * than as an opaque hex blob nobody can check against the document.
     */
    private static final byte[] RFC_SHA1_KEY =
        "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    /** The RFC 6238 SHA-256 key: the same pattern, extended to 32 bytes. */
    private static final byte[] RFC_SHA256_KEY =
        "12345678901234567890123456789012".getBytes(StandardCharsets.US_ASCII);

    @Nested
    @DisplayName("RFC 6238 Appendix B vectors")
    class Rfc6238 {

        /** Appendix B tabulates 8-digit codes, so the generator is built for 8. */
        private final TimeBasedOneTimePassword sha1 =
            new TimeBasedOneTimePassword("HmacSHA1", 30, 8);

        @ParameterizedTest(name = "at {0}s the SHA-1 code is {1}")
        @CsvSource({
            "59,          94287082",
            "1111111109,  07081804",
            "1111111111,  14050471",
            "1234567890,  89005924",
            "2000000000,  69279037",
            "20000000000, 65353130"
        })
        @DisplayName("SHA-1")
        void sha1Vectors(long epochSecond, String expected) {
            assertThat(sha1.generate(RFC_SHA1_KEY, Instant.ofEpochSecond(epochSecond)))
                .isEqualTo(expected);
        }

        @ParameterizedTest(name = "at {0}s the SHA-256 code is {1}")
        @CsvSource({
            "59,          46119246",
            "1111111109,  68084774",
            "1111111111,  67062674",
            "1234567890,  91819424",
            "2000000000,  90698825",
            "20000000000, 77737706"
        })
        @DisplayName("SHA-256")
        void sha256Vectors(long epochSecond, String expected) {
            assertThat(new TimeBasedOneTimePassword("HmacSHA256", 30, 8)
                .generate(RFC_SHA256_KEY, Instant.ofEpochSecond(epochSecond)))
                .isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("RFC 4226 Appendix D vectors")
    class Rfc4226 {

        /**
         * HOTP is TOTP with the counter supplied directly, so the same code
         * path is exercised by feeding step numbers 0 to 9. If the dynamic
         * truncation were wrong, these would diverge before the TOTP vectors
         * did, and at a step small enough to debug by hand.
         */
        private final TimeBasedOneTimePassword hotp =
            new TimeBasedOneTimePassword("HmacSHA1", 30, 6);

        @ParameterizedTest(name = "counter {0} gives {1}")
        @CsvSource({
            "0, 755224", "1, 287082", "2, 359152", "3, 969429", "4, 338314",
            "5, 254676", "6, 287922", "7, 162583", "8, 399871", "9, 520489"
        })
        @DisplayName("six-digit HOTP")
        void hotpVectors(long counter, String expected) {
            assertThat(hotp.generateForStep(RFC_SHA1_KEY, counter)).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("verification")
    class Verification {

        private final TimeBasedOneTimePassword totp = new TimeBasedOneTimePassword();
        private final Instant now = Instant.parse("2026-08-27T12:00:00Z");
        private final byte[] secret = RFC_SHA1_KEY;

        @Test
        @DisplayName("accepts the current code")
        void acceptsCurrent() {
            String code = totp.generate(secret, now);

            assertThat(totp.verify(secret, code, now, -1)).isPresent();
        }

        @Test
        @DisplayName("accepts a code from one step ago")
        void acceptsPreviousStep() {
            // The common real case: the user reads the code as the step turns
            // over and types it a few seconds later.
            String code = totp.generate(secret, now.minusSeconds(30));

            assertThat(totp.verify(secret, code, now, -1)).isPresent();
        }

        @Test
        @DisplayName("accepts a code from one step ahead")
        void acceptsNextStep() {
            String code = totp.generate(secret, now.plusSeconds(30));

            assertThat(totp.verify(secret, code, now, -1)).isPresent();
        }

        @Test
        @DisplayName("refuses a code two steps old")
        void refusesOutsideWindow() {
            // Each extra step of tolerance multiplies an attacker's odds
            // against a six-digit code by three, so the window stays at one.
            String code = totp.generate(secret, now.minusSeconds(90));

            assertThat(totp.verify(secret, code, now, -1)).isEmpty();
        }

        @Test
        @DisplayName("refuses a wrong code")
        void refusesWrongCode() {
            assertThat(totp.verify(secret, "000000", now, -1)).isEmpty();
        }

        @Test
        @DisplayName("refuses a code of the wrong length rather than padding it")
        void refusesWrongLength() {
            assertThat(totp.verify(secret, "1234", now, -1)).isEmpty();
            assertThat(totp.verify(secret, "12345678", now, -1)).isEmpty();
            assertThat(totp.verify(secret, "", now, -1)).isEmpty();
            assertThat(totp.verify(secret, null, now, -1)).isEmpty();
        }

        @Test
        @DisplayName("reports which step matched, so the caller can burn it")
        void reportsTheStep() {
            String code = totp.generate(secret, now);

            OptionalLong step = totp.verify(secret, code, now, -1);

            assertThat(step).hasValue(totp.timeStepAt(now));
        }

        @Test
        @DisplayName("refuses a code that was already used")
        void refusesReplay() {
            String code = totp.generate(secret, now);
            long used = totp.verify(secret, code, now, -1).orElseThrow();

            // Without this, a code read over someone's shoulder stays usable
            // for the rest of its 90-second window.
            assertThat(totp.verify(secret, code, now, used)).isEmpty();
        }

        @Test
        @DisplayName("refuses an older code once a newer one has been used")
        void refusesOlderThanLastUsed() {
            long currentStep = totp.timeStepAt(now);
            String previous = totp.generate(secret, now.minusSeconds(30));

            // Replay protection is "at or below the last used step", not "not
            // exactly the last step" — otherwise the previous step's code stays
            // live after the current one has been spent.
            assertThat(totp.verify(secret, previous, now, currentStep)).isEmpty();
        }

        @Test
        @DisplayName("a different secret does not open the account")
        void secretsAreDistinct() {
            String code = totp.generate(secret, now);
            byte[] someoneElse = "09876543210987654321".getBytes(StandardCharsets.US_ASCII);

            assertThat(totp.verify(someoneElse, code, now, -1)).isEmpty();
        }
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("refuses a digit count outside what RFC 4226 defines")
        void refusesBadDigits() {
            for (int digits : new int[] {0, 5, 9, -1}) {
                org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> new TimeBasedOneTimePassword("HmacSHA1", 30, digits))
                    .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        @DisplayName("refuses a non-positive time step")
        void refusesBadTimeStep() {
            org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new TimeBasedOneTimePassword("HmacSHA1", 0, 6))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("defaults to what authenticator apps expect")
        void sensibleDefaults() {
            TimeBasedOneTimePassword totp = new TimeBasedOneTimePassword();

            assertThat(totp.digits()).isEqualTo(6);
            assertThat(totp.timeStepSeconds()).isEqualTo(30);
            assertThat(totp.algorithmLabel()).isEqualTo("SHA1");
        }
    }
}
