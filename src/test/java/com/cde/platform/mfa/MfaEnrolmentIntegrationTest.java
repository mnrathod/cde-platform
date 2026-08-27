package com.cde.platform.mfa;

import com.cde.platform.model.User;
import com.cde.platform.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Second-factor enrolment against a real database.
 *
 * <p>The arithmetic is covered by {@link TimeBasedOneTimePasswordTest} against
 * the RFC vectors. What needs a database is everything around it: that the
 * secret is stored encrypted and not in the clear, that replay protection
 * survives a round trip through the row, that recovery codes are single use,
 * and that an unconfirmed enrolment is not a factor.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "cde.mfa.enabled=true",
    // Test-only key, 32 bytes when decoded. Fixed rather than random so a
    // failure is reproducible.
    "cde.mfa.secret-encryption-key=dGVzdC1vbmx5LW1mYS1rZXktMzItYnl0ZXMtbG9uZyE="
})
@DisplayName("multi-factor enrolment")
class MfaEnrolmentIntegrationTest {

    @Autowired MfaEnrolmentService service;
    @Autowired MfaEnrolmentRepository enrolments;
    @Autowired RecoveryCodeRepository recoveryCodes;
    @Autowired SecretEncryption encryption;
    @Autowired TimeBasedOneTimePassword totp;
    @Autowired UserRepository users;

    private User user;

    @BeforeEach
    void createUser() {
        String unique = "mfa-" + System.nanoTime();
        user = users.save(User.builder()
            .username(unique)
            .email(unique + "@example.invalid")
            .password("{noop}irrelevant-to-these-tests")
            .role(User.Role.VIEWER)
            .build());
    }

    /** The code an authenticator would show right now for this enrolment. */
    private String currentCode() {
        MfaEnrolment enrolment = enrolments.findByUserId(user.getId()).orElseThrow();
        return totp.generate(encryption.decrypt(enrolment.encryptedSecret()), Instant.now());
    }

    private List<String> enrolFully() {
        service.beginEnrolment(user.getId(), user.getUsername());
        return service.confirmEnrolment(user.getId(), user.getUsername(), currentCode());
    }

    @Nested
    @DisplayName("enrolment")
    class Enrolment {

        @Test
        @DisplayName("hands back a scannable URI and a typable secret")
        void producesBothEntryPaths() {
            var invitation = service.beginEnrolment(user.getId(), user.getUsername());

            assertThat(invitation.otpAuthUri())
                .startsWith("otpauth://totp/")
                .contains("issuer=")
                .contains("algorithm=SHA1")
                .contains("digits=6")
                .contains("period=30");
            // Manual entry has to work too: QR scanning fails often enough, and
            // some users enrol on the same device that shows the code.
            assertThat(invitation.base32Secret()).isNotBlank();
        }

        @Test
        @DisplayName("stores the secret encrypted, not in the clear")
        void secretIsEncryptedAtRest() {
            var invitation = service.beginEnrolment(user.getId(), user.getUsername());

            String stored = enrolments.findByUserId(user.getId()).orElseThrow().encryptedSecret();

            // A TOTP secret has nothing to crack — the plaintext is the
            // credential — so a database dump holding it is a dump of live
            // second factors.
            assertThat(stored).isNotEqualTo(invitation.base32Secret());
            assertThat(Base32.encode(encryption.decrypt(stored)))
                .isEqualTo(invitation.base32Secret());
        }

        @Test
        @DisplayName("is not a second factor until a code proves it works")
        void unconfirmedIsNotAFactor() {
            service.beginEnrolment(user.getId(), user.getUsername());

            // Activating on "the QR code was displayed" locks people out
            // whenever the scan silently failed or the phone's clock is wrong.
            assertThat(service.isEnrolled(user.getId())).isFalse();
            assertThat(service.verify(user.getId(), user.getUsername(), currentCode())).isFalse();
        }

        @Test
        @DisplayName("becomes a factor once confirmed")
        void confirmedIsAFactor() {
            enrolFully();

            assertThat(service.isEnrolled(user.getId())).isTrue();
        }

        @Test
        @DisplayName("a wrong code does not confirm")
        void wrongCodeDoesNotConfirm() {
            service.beginEnrolment(user.getId(), user.getUsername());

            assertThatThrownBy(() ->
                service.confirmEnrolment(user.getId(), user.getUsername(), "000000"))
                .isInstanceOf(InvalidMfaCodeException.class);
            assertThat(service.isEnrolled(user.getId())).isFalse();
        }

        @Test
        @DisplayName("restarting an abandoned enrolment issues a fresh secret")
        void restartReplacesUnconfirmed() {
            var first = service.beginEnrolment(user.getId(), user.getUsername());
            var second = service.beginEnrolment(user.getId(), user.getUsername());

            // The abandoned secret was never proved to exist in any app, so
            // replacing it costs nothing and keeps setup restartable.
            assertThat(second.base32Secret()).isNotEqualTo(first.base32Secret());
        }

        @Test
        @DisplayName("a confirmed enrolment cannot be silently replaced")
        void confirmedCannotBeReplaced() {
            enrolFully();

            // Quietly issuing a new secret would let anyone with a live session
            // swap out the second factor — the precise escalation it exists to
            // prevent.
            assertThatThrownBy(() -> service.beginEnrolment(user.getId(), user.getUsername()))
                .isInstanceOf(MfaAlreadyEnrolledException.class);
        }
    }

    @Nested
    @DisplayName("verification")
    class Verification {

        @Test
        @DisplayName("accepts the current code")
        void acceptsCurrentCode() {
            enrolFully();
            // A fresh step, because confirmation burned the one it used.
            String next = totp.generate(
                encryption.decrypt(enrolments.findByUserId(user.getId()).orElseThrow()
                    .encryptedSecret()),
                Instant.now().plusSeconds(30));

            assertThat(service.verify(user.getId(), user.getUsername(), next)).isTrue();
        }

        @Test
        @DisplayName("refuses the code that was used to confirm")
        void confirmationCodeIsBurned() {
            service.beginEnrolment(user.getId(), user.getUsername());
            String code = currentCode();
            service.confirmEnrolment(user.getId(), user.getUsername(), code);

            // Otherwise the code a user just typed into setup is a valid
            // sign-in for the rest of its window.
            assertThat(service.verify(user.getId(), user.getUsername(), code)).isFalse();
        }

        @Test
        @DisplayName("refuses a code that was already used, across a database round trip")
        void replayProtectionPersists() {
            enrolFully();
            String next = totp.generate(
                encryption.decrypt(enrolments.findByUserId(user.getId()).orElseThrow()
                    .encryptedSecret()),
                Instant.now().plusSeconds(30));

            assertThat(service.verify(user.getId(), user.getUsername(), next)).isTrue();
            // The second call re-reads the row, so this proves the burnt step
            // was persisted and not merely held in memory.
            assertThat(service.verify(user.getId(), user.getUsername(), next)).isFalse();
        }

        @Test
        @DisplayName("a user with no enrolment does not verify")
        void notEnrolledDoesNotVerify() {
            // A caller treating "not enrolled" as a pass has removed the factor
            // for everyone who never set one up.
            assertThat(service.verify(user.getId(), user.getUsername(), "123456")).isFalse();
        }
    }

    @Nested
    @DisplayName("recovery codes")
    class Recovery {

        @Test
        @DisplayName("ten are issued on confirmation")
        void tenIssued() {
            assertThat(enrolFully()).hasSize(10);
            assertThat(service.remainingRecoveryCodes(user.getId())).isEqualTo(10);
        }

        @Test
        @DisplayName("they are stored hashed, never in the clear")
        void storedHashed() {
            List<String> issued = enrolFully();

            List<String> stored = recoveryCodes.findByUserId(user.getId()).stream()
                .map(Object::toString).toList();

            // toString is written to omit the hash, so this checks the plaintext
            // is not recoverable from what the entity will render into a log.
            assertThat(stored).noneMatch(rendered ->
                issued.stream().anyMatch(rendered::contains));
        }

        @Test
        @DisplayName("one redeems")
        void redeems() {
            List<String> issued = enrolFully();

            assertThat(service.redeemRecoveryCode(user.getId(), user.getUsername(), issued.get(0)))
                .isTrue();
            assertThat(service.remainingRecoveryCodes(user.getId())).isEqualTo(9);
        }

        @Test
        @DisplayName("the same one does not redeem twice")
        void singleUse() {
            List<String> issued = enrolFully();
            service.redeemRecoveryCode(user.getId(), user.getUsername(), issued.get(0));

            assertThat(service.redeemRecoveryCode(user.getId(), user.getUsername(), issued.get(0)))
                .isFalse();
        }

        @Test
        @DisplayName("accepts the formatting the user was shown")
        void tolerantOfFormatting() {
            List<String> issued = enrolFully();
            String code = issued.get(0);
            String asDisplayed = code.substring(0, 5) + "-" + code.substring(5);

            // Refusing the product's own display format turns recovery into a
            // support call at the exact moment the user has lost their phone.
            assertThat(service.redeemRecoveryCode(user.getId(), user.getUsername(),
                asDisplayed.toLowerCase(java.util.Locale.ROOT))).isTrue();
        }

        @Test
        @DisplayName("an invented code does not redeem")
        void refusesUnknown() {
            enrolFully();

            assertThat(service.redeemRecoveryCode(user.getId(), user.getUsername(), "ZZZZZZZZZZ"))
                .isFalse();
        }

        @Test
        @DisplayName("regenerating invalidates every previous code")
        void regenerationInvalidatesOld() {
            List<String> original = enrolFully();

            List<String> replacement =
                service.regenerateRecoveryCodes(user.getId(), user.getUsername());

            assertThat(replacement).hasSize(10).doesNotContainAnyElementsOf(original);
            assertThat(service.redeemRecoveryCode(user.getId(), user.getUsername(), original.get(0)))
                .isFalse();
            assertThat(service.redeemRecoveryCode(user.getId(), user.getUsername(),
                replacement.get(0))).isTrue();
        }

        @Test
        @DisplayName("every issued code is distinct")
        void codesAreDistinct() {
            assertThat(enrolFully()).doesNotHaveDuplicates();
        }
    }

    @Nested
    @DisplayName("disabling")
    class Disabling {

        @Test
        @DisplayName("removes the factor and its recovery codes together")
        void removesEverything() {
            List<String> issued = enrolFully();

            service.disable(user.getId(), user.getUsername());

            assertThat(service.isEnrolled(user.getId())).isFalse();
            assertThat(service.remainingRecoveryCodes(user.getId())).isZero();
            // Orphaned recovery codes would keep working against an account
            // with no second factor at all.
            assertThat(service.redeemRecoveryCode(user.getId(), user.getUsername(), issued.get(0)))
                .isFalse();
        }

        @Test
        @DisplayName("enrolment can start again afterwards")
        void allowsReEnrolment() {
            enrolFully();
            service.disable(user.getId(), user.getUsername());

            assertThat(service.beginEnrolment(user.getId(), user.getUsername())).isNotNull();
        }
    }
}
