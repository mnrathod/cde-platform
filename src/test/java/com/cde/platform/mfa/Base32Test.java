package com.cde.platform.mfa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Base32 against the vectors in RFC 4648 section 10.
 *
 * <p>Same principle as the TOTP tests: the expected strings come from the
 * specification, so this checks the implementation against the standard
 * rather than against its own output.
 */
@DisplayName("base32")
class Base32Test {

    @ParameterizedTest(name = "\"{0}\" encodes to {1}")
    @CsvSource({
        "'',       ''",
        "f,        MY",
        "fo,       MZXQ",
        "foo,      MZXW6",
        "foob,     MZXW6YQ",
        "fooba,    MZXW6YTB",
        "foobar,   MZXW6YTBOI"
    })
    @DisplayName("RFC 4648 section 10 vectors, unpadded")
    void rfcVectors(String plain, String encoded) {
        // Unpadded, because that is what authenticator apps emit and accept.
        assertThat(Base32.encode(plain.getBytes(StandardCharsets.US_ASCII)))
            .isEqualTo(encoded == null ? "" : encoded);
    }

    @ParameterizedTest(name = "{1} decodes back to \"{0}\"")
    @CsvSource({
        "f,        MY",
        "fo,       MZXQ",
        "foo,      MZXW6",
        "foob,     MZXW6YQ",
        "fooba,    MZXW6YTB",
        "foobar,   MZXW6YTBOI"
    })
    @DisplayName("decoding reverses encoding")
    void decodesRfcVectors(String plain, String encoded) {
        assertThat(new String(Base32.decode(encoded), StandardCharsets.US_ASCII))
            .isEqualTo(plain);
    }

    @Test
    @DisplayName("a secret survives a round trip")
    void roundTrip() {
        byte[] secret = new byte[20];
        for (int i = 0; i < secret.length; i++) secret[i] = (byte) (i * 7 + 3);

        assertThat(Base32.decode(Base32.encode(secret))).isEqualTo(secret);
    }

    @ParameterizedTest(name = "accepts {0} as typed by a person")
    @ValueSource(strings = {
        "MZXW6YTBOI",
        "mzxw6ytboi",
        "MZXW 6YTB OI",
        "MZXW-6YTB-OI",
        "MZXW6YTBOI======"
    })
    @DisplayName("tolerates the spacing, case and padding a manual entry carries")
    void tolerantOfPresentation(String typed) {
        // Manual-entry secrets are read off a screen and typed, usually in
        // groups of four. Refusing the spaces would be needlessly hostile.
        assertThat(new String(Base32.decode(typed), StandardCharsets.US_ASCII))
            .isEqualTo("foobar");
    }

    @ParameterizedTest(name = "refuses {0}")
    @ValueSource(strings = {
        // 0, 1, 8 and 9 are deliberately absent from the base32 alphabet,
        // precisely because they are confusable with O, I, B and g when a
        // secret is read off a screen.
        "MZXW6YTB01", "MZXW6YTB89", "MZXW6YTB!", "MZXW+YTB", "8888"})
    @DisplayName("refuses a character outside the alphabet rather than skipping it")
    void strictAboutContent(String invalid) {
        // Skipping an unexpected character would let a mistyped secret enrol
        // successfully and then reject every code afterwards — a miserable
        // thing to debug from a support ticket.
        assertThatThrownBy(() -> Base32.decode(invalid))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("refuses nothing at all")
    void refusesEmpty() {
        assertThatThrownBy(() -> Base32.decode(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Base32.decode("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Base32.decode("   ")).isInstanceOf(IllegalArgumentException.class);
    }
}
