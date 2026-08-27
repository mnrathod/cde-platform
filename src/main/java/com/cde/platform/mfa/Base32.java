package com.cde.platform.mfa;

import java.util.Locale;

/**
 * Base32 as RFC 4648 defines it, which is how TOTP secrets are exchanged.
 *
 * <p>The JDK ships Base64 but not Base32, and authenticator apps take Base32
 * — both in the QR code's {@code otpauth://} URI and in the manual-entry
 * string. This is the alphabet and the 5-bits-to-8-bits packing, nothing more.
 *
 * <p>Decoding is deliberately lenient about presentation and strict about
 * content: whitespace and case are normalised, because a manual-entry string
 * is read off a screen and typed by a person, but anything outside the
 * alphabet is refused rather than skipped. Silently ignoring an unexpected
 * character would let a mistyped secret enrol successfully and then fail
 * every code afterwards, which is a miserable thing to debug.
 */
public final class Base32 {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int BITS_PER_CHARACTER = 5;
    private static final int BITS_PER_BYTE = 8;

    private Base32() {
    }

    /** Encodes without padding, which is what authenticator apps expect. */
    public static String encode(byte[] data) {
        StringBuilder encoded = new StringBuilder((data.length * BITS_PER_BYTE + 4) / BITS_PER_CHARACTER);
        int buffer = 0;
        int bitsHeld = 0;

        for (byte current : data) {
            buffer = (buffer << BITS_PER_BYTE) | (current & 0xFF);
            bitsHeld += BITS_PER_BYTE;
            while (bitsHeld >= BITS_PER_CHARACTER) {
                bitsHeld -= BITS_PER_CHARACTER;
                encoded.append(ALPHABET.charAt((buffer >> bitsHeld) & 0x1F));
            }
        }
        if (bitsHeld > 0) {
            // Left-align the remaining bits into a final character.
            encoded.append(ALPHABET.charAt((buffer << (BITS_PER_CHARACTER - bitsHeld)) & 0x1F));
        }
        return encoded.toString();
    }

    /**
     * Decodes, tolerating the spacing and case a person types.
     *
     * @throws IllegalArgumentException on any character outside the alphabet
     */
    public static byte[] decode(String encoded) {
        if (encoded == null) {
            throw new IllegalArgumentException("No secret supplied");
        }
        // Spaces are how manual-entry secrets are displayed, in groups of four;
        // padding is optional in RFC 4648 and some apps emit it.
        String normalised = encoded.replace(" ", "").replace("-", "")
            .replace("=", "").toUpperCase(Locale.ROOT);
        if (normalised.isEmpty()) {
            throw new IllegalArgumentException("No secret supplied");
        }

        java.io.ByteArrayOutputStream decoded = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bitsHeld = 0;

        for (int index = 0; index < normalised.length(); index++) {
            int value = ALPHABET.indexOf(normalised.charAt(index));
            if (value < 0) {
                throw new IllegalArgumentException(
                    "Not a base32 character at position " + index + ": " + normalised.charAt(index));
            }
            buffer = (buffer << BITS_PER_CHARACTER) | value;
            bitsHeld += BITS_PER_CHARACTER;
            if (bitsHeld >= BITS_PER_BYTE) {
                bitsHeld -= BITS_PER_BYTE;
                decoded.write((buffer >> bitsHeld) & 0xFF);
            }
        }
        return decoded.toByteArray();
    }
}
