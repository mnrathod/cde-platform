package com.cde.platform.mfa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sealing a TOTP secret so a database dump does not hand over everybody's
 * second factor.
 *
 * <p>§5.2 asks for application-layer encryption on exactly this field, and the
 * reason is narrow: a TOTP secret is a bearer credential. Anyone holding it can
 * produce valid codes indefinitely, so a raw dump of the table would be a
 * complete MFA bypass for every account in it, silently and with nothing to
 * revoke.
 *
 * <p>The cases that matter are the refusals. AES-GCM fails closed when the
 * ciphertext has been touched, and that property is the whole value of using
 * GCM rather than CBC — but it only holds if the tag is actually being checked,
 * which is something a round-trip test alone cannot tell you. Each way of
 * corrupting stored data is given its own case because they take different
 * paths: a truncated value never reaches the cipher, a flipped byte reaches it
 * and fails authentication.
 */
@DisplayName("sealing a stored secret")
class SecretEncryptionTest {

    /** Thirty-two bytes, which is what AES-256 takes. */
    private static byte[] key(int seed) {
        byte[] key = new byte[32];
        for (int index = 0; index < key.length; index++) key[index] = (byte) (seed + index);
        return key;
    }

    private final SecretEncryption encryption = new SecretEncryption(key(1));

    private static byte[] secret(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("the key it is built with")
    class KeyMaterial {

        @Test
        @DisplayName("thirty-two bytes is accepted")
        void acceptsA256BitKey() {
            assertThat(new SecretEncryption(key(7))).isNotNull();
        }

        @Test
        @DisplayName("a short key is refused, and says how short")
        void refusesAShortKey() {
            // Silently padding or hashing a short key to length would produce
            // a working cipher at a strength nobody chose.
            assertThatThrownBy(() -> new SecretEncryption(new byte[16]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes")
                .hasMessageContaining("16 bytes");
        }

        @Test
        @DisplayName("a long key is refused too, rather than truncated")
        void refusesALongKey() {
            assertThatThrownBy(() -> new SecretEncryption(new byte[64]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64 bytes");
        }

        @Test
        @DisplayName("no key at all is refused, and says so rather than printing a length")
        void refusesNoKey() {
            assertThatThrownBy(() -> new SecretEncryption(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("none");
        }

        @Test
        @DisplayName("an empty key is refused")
        void refusesAnEmptyKey() {
            assertThatThrownBy(() -> new SecretEncryption(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("sealing and opening again")
    class RoundTrip {

        @Test
        @DisplayName("what goes in comes back out")
        void roundTrips() {
            String sealed = encryption.encrypt(secret("JBSWY3DPEHPK3PXP"));

            assertThat(encryption.decrypt(sealed))
                .isEqualTo(secret("JBSWY3DPEHPK3PXP"));
        }

        @Test
        @DisplayName("the stored form does not contain the secret")
        void storedFormHidesTheSecret() {
            // The point of the exercise. If the secret were readable in the
            // stored value, everything else here would be decoration.
            String sealed = encryption.encrypt(secret("JBSWY3DPEHPK3PXP"));

            assertThat(sealed).doesNotContain("JBSWY3DPEHPK3PXP");
        }

        @Test
        @DisplayName("sealing the same secret twice gives two different values")
        void sealingIsNotDeterministic() {
            // A fresh nonce each time. Deterministic ciphertext would let
            // anyone with the table see which accounts share a secret, and
            // §5.2 permits determinism only where equality search is
            // unavoidable — it is not, here.
            String first  = encryption.encrypt(secret("same secret"));
            String second = encryption.encrypt(secret("same secret"));

            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("both of those still open to the same secret")
        void bothOpenToTheSameThing() {
            String first  = encryption.encrypt(secret("same secret"));
            String second = encryption.encrypt(secret("same secret"));

            assertThat(encryption.decrypt(first)).isEqualTo(encryption.decrypt(second));
        }

        @Test
        @DisplayName("an empty secret round-trips rather than failing")
        void handlesAnEmptySecret() {
            assertThat(encryption.decrypt(encryption.encrypt(new byte[0]))).isEmpty();
        }

        @Test
        @DisplayName("a long secret round-trips")
        void handlesALongSecret() {
            byte[] long_ = new byte[4096];
            for (int index = 0; index < long_.length; index++) long_[index] = (byte) index;

            assertThat(encryption.decrypt(encryption.encrypt(long_))).isEqualTo(long_);
        }

        @Test
        @DisplayName("the stored form is base64, so it survives a text column")
        void storedFormIsBase64() {
            String sealed = encryption.encrypt(secret("x"));

            assertThat(Base64.getDecoder().decode(sealed)).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("stored data that cannot be trusted")
    class Refusals {

        @Test
        @DisplayName("a value that is not base64 is refused, and says which fault it is")
        void refusesNonBase64() {
            // Distinguished from a decryption failure on purpose: one is a
            // corrupted column, the other a wrong key, and they send an
            // operator to different places.
            assertThatThrownBy(() -> encryption.decrypt("this is not base64 !!!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base64");
        }

        @Test
        @DisplayName("a value too short to hold a nonce is refused before the cipher sees it")
        void refusesATruncatedValue() {
            String tooShort = Base64.getEncoder().encodeToString(new byte[8]);

            assertThatThrownBy(() -> encryption.decrypt(tooShort))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too short");
        }

        @Test
        @DisplayName("a value of exactly the nonce length is refused — there is no ciphertext")
        void refusesANonceWithNothingAfterIt() {
            // The boundary. Twelve bytes is a nonce and nothing else, and an
            // off-by-one here would hand an empty buffer to the cipher.
            String nonceOnly = Base64.getEncoder().encodeToString(new byte[12]);

            assertThatThrownBy(() -> encryption.decrypt(nonceOnly))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too short");
        }

        @Test
        @DisplayName("a flipped byte in the ciphertext is refused, not silently decrypted")
        void refusesATamperedValue() {
            // This is what GCM is for, and the only case that shows the tag is
            // really being checked: a round-trip test passes either way.
            byte[] sealed = Base64.getDecoder()
                .decode(encryption.encrypt(secret("JBSWY3DPEHPK3PXP")));
            sealed[sealed.length - 1] ^= 0x01;

            assertThatThrownBy(() ->
                encryption.decrypt(Base64.getEncoder().encodeToString(sealed)))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("a flipped byte in the nonce is refused as well")
        void refusesATamperedNonce() {
            byte[] sealed = Base64.getDecoder()
                .decode(encryption.encrypt(secret("JBSWY3DPEHPK3PXP")));
            sealed[0] ^= 0x01;

            assertThatThrownBy(() ->
                encryption.decrypt(Base64.getEncoder().encodeToString(sealed)))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("another deployment's key does not open this one's secrets")
        void refusesTheWrongKey() {
            // The property that makes per-deployment keys worth having, and
            // the one a restore into the wrong environment depends on.
            String sealed = encryption.encrypt(secret("JBSWY3DPEHPK3PXP"));

            assertThatThrownBy(() -> new SecretEncryption(key(99)).decrypt(sealed))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("the refusal never quotes the secret it failed to open")
        void refusalDoesNotLeakTheSecret() {
            // §5.7: an exception message reaches a log, and a log is not a
            // place for MFA material even in a failure.
            byte[] sealed = Base64.getDecoder()
                .decode(encryption.encrypt(secret("JBSWY3DPEHPK3PXP")));
            sealed[sealed.length - 1] ^= 0x01;
            String tampered = Base64.getEncoder().encodeToString(sealed);

            assertThatThrownBy(() -> encryption.decrypt(tampered))
                .satisfies(thrown -> assertThat(String.valueOf(thrown.getMessage()))
                    .doesNotContain("JBSWY3DPEHPK3PXP"));
        }
    }
}
