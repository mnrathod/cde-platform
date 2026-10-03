package com.cde.platform.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether an invitation may still be used, and what it says about itself.
 *
 * <p>An invitation is the authority to join a tenant, which §18 calls the most
 * consequential permission the platform has: everything else only decides what
 * somebody already inside may do. So the question "may this one still be
 * redeemed?" is a security decision, and it has three independent ways to be
 * no — used, withdrawn, or out of time. Each has to be checked, because any one
 * of them being skipped means a link that should be dead still lets somebody
 * in.
 *
 * <p>Pure unit tests with an injected clock value. The decision is a comparison
 * of four fields, and putting a database behind it would only make it slower to
 * find out which comparison is wrong. The combinations matter more than any one
 * of them: an invitation that is both accepted and expired must report the
 * reason a person would act on, not whichever check happens to run first.
 */
@DisplayName("whether an invitation can still be used")
class InvitationStateTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 3, 12, 0);

    /** An invitation in whatever state the fields describe. */
    private Invitation invitation(LocalDateTime expiresAt,
                                  LocalDateTime acceptedAt,
                                  LocalDateTime revokedAt) {
        return Invitation.builder()
            .email("sam.okonkwo@example.test")
            .role(User.Role.ENGINEER)
            .tokenHash("a".repeat(64))
            .expiresAt(expiresAt)
            .acceptedAt(acceptedAt)
            .revokedAt(revokedAt)
            .build();
    }

    private Invitation pending() {
        return invitation(NOW.plusDays(7), null, null);
    }

    @Nested
    @DisplayName("deciding whether it may be redeemed")
    class Redeemability {

        @Test
        @DisplayName("an unused invitation inside its window may be redeemed")
        void pendingIsRedeemable() {
            assertThat(pending().isRedeemable(NOW)).isTrue();
        }

        @Test
        @DisplayName("one that has already been accepted may not be redeemed again")
        void acceptedIsNotRedeemable() {
            // Otherwise one link admits any number of accounts, which is the
            // difference between an invitation and an open registration.
            assertThat(invitation(NOW.plusDays(7), NOW.minusHours(1), null)
                .isRedeemable(NOW)).isFalse();
        }

        @Test
        @DisplayName("one that has been withdrawn may not be redeemed")
        void revokedIsNotRedeemable() {
            // Revocation is the only remedy once a link has been sent to the
            // wrong address, so it has to actually stop it.
            assertThat(invitation(NOW.plusDays(7), null, NOW.minusHours(1))
                .isRedeemable(NOW)).isFalse();
        }

        @Test
        @DisplayName("one that has run out of time may not be redeemed")
        void expiredIsNotRedeemable() {
            assertThat(invitation(NOW.minusMinutes(1), null, null)
                .isRedeemable(NOW)).isFalse();
        }

        @Test
        @DisplayName("the expiry moment itself is already too late")
        void theExpiryMomentIsTooLate() {
            // The boundary. An inclusive comparison here leaves a link usable
            // for one more tick, which is a small window and still a window.
            assertThat(invitation(NOW, null, null).isRedeemable(NOW)).isFalse();
        }

        @Test
        @DisplayName("a moment before expiry is still in time")
        void justBeforeExpiryIsInTime() {
            assertThat(invitation(NOW.plusSeconds(1), null, null).isRedeemable(NOW)).isTrue();
        }

        @Test
        @DisplayName("accepted and expired is still refused")
        void acceptedAndExpiredIsRefused() {
            assertThat(invitation(NOW.minusDays(1), NOW.minusDays(2), null)
                .isRedeemable(NOW)).isFalse();
        }

        @Test
        @DisplayName("withdrawn and accepted is still refused")
        void revokedAndAcceptedIsRefused() {
            assertThat(invitation(NOW.plusDays(7), NOW.minusHours(2), NOW.minusHours(1))
                .isRedeemable(NOW)).isFalse();
        }
    }

    @Nested
    @DisplayName("deciding whose invitation it is")
    class Addressing {

        @Test
        @DisplayName("admits the address it was sent to")
        void admitsTheAddressee() {
            assertThat(pending().admits("sam.okonkwo@example.test")).isTrue();
        }

        @Test
        @DisplayName("admits it whatever case it was typed in")
        void addressIsCaseInsensitive() {
            // People type their own address in whatever case their keyboard was
            // in, and an invitation that refused them over it would be
            // indistinguishable from one that had expired.
            assertThat(pending().admits("Sam.Okonkwo@Example.Test")).isTrue();
        }

        @Test
        @DisplayName("forgives surrounding whitespace, which a paste brings along")
        void addressIsTrimmed() {
            assertThat(pending().admits("  sam.okonkwo@example.test  ")).isTrue();
        }

        @Test
        @DisplayName("does not admit a different address")
        void refusesAnotherAddress() {
            // The control: an invitation is addressed, so somebody who
            // intercepted the link cannot redeem it as themselves.
            assertThat(pending().admits("someone.else@example.test")).isFalse();
        }

        @Test
        @DisplayName("does not admit an absent address")
        void refusesNoAddress() {
            assertThat(pending().admits(null)).isFalse();
        }

        @Test
        @DisplayName("does not admit a blank address")
        void refusesABlankAddress() {
            assertThat(pending().admits("   ")).isFalse();
        }

        @Test
        @DisplayName("does not admit an address that merely contains the right one")
        void refusesASupersetAddress() {
            // An equality check rather than a contains check. The difference
            // matters: evil.example.test/sam.okonkwo@example.test contains it.
            assertThat(pending().admits("x-sam.okonkwo@example.test")).isFalse();
        }
    }

    @Nested
    @DisplayName("what it says about itself")
    class Status {

        @Test
        @DisplayName("an unused invitation inside its window is pending")
        void pendingReportsPending() {
            assertThat(pending().describeStatus(NOW)).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("an accepted invitation reports that it was accepted")
        void acceptedReportsAccepted() {
            assertThat(invitation(NOW.plusDays(7), NOW.minusHours(1), null)
                .describeStatus(NOW)).isEqualTo("ACCEPTED");
        }

        @Test
        @DisplayName("a withdrawn invitation reports that it was withdrawn")
        void revokedReportsRevoked() {
            assertThat(invitation(NOW.plusDays(7), null, NOW.minusHours(1))
                .describeStatus(NOW)).isEqualTo("REVOKED");
        }

        @Test
        @DisplayName("one that ran out of time reports that it expired")
        void expiredReportsExpired() {
            assertThat(invitation(NOW.minusMinutes(1), null, null)
                .describeStatus(NOW)).isEqualTo("EXPIRED");
        }

        @Test
        @DisplayName("an accepted invitation that later expired still reports accepted")
        void acceptanceOutranksExpiry() {
            // The one an administrator reads off a list: "expired" would
            // suggest the person never joined, when they did.
            assertThat(invitation(NOW.minusDays(1), NOW.minusDays(2), null)
                .describeStatus(NOW)).isEqualTo("ACCEPTED");
        }

        @Test
        @DisplayName("a withdrawn invitation that later expired reports withdrawn")
        void revocationOutranksExpiry() {
            assertThat(invitation(NOW.minusDays(1), null, NOW.minusDays(2))
                .describeStatus(NOW)).isEqualTo("REVOKED");
        }

        @Test
        @DisplayName("one both accepted and withdrawn reports accepted, which happened first")
        void acceptanceOutranksRevocation() {
            // Withdrawing after acceptance does not un-join the account — that
            // is a role change, not a revocation — so reporting REVOKED here
            // would describe a state the tenant is not in.
            assertThat(invitation(NOW.plusDays(7), NOW.minusHours(2), NOW.minusHours(1))
                .describeStatus(NOW)).isEqualTo("ACCEPTED");
        }

        @ParameterizedTest
        @ValueSource(strings = {"PENDING", "ACCEPTED", "REVOKED", "EXPIRED"})
        @DisplayName("every status it reports is one of the four it documents")
        void statusesAreFromTheDocumentedSet(String status) {
            // Named here so a fifth one added to the entity has to be added to
            // the API's description too, rather than reaching a client that
            // does not know it.
            assertThat(java.util.Set.of("PENDING", "ACCEPTED", "REVOKED", "EXPIRED"))
                .contains(status);
        }

        @Test
        @DisplayName("a redeemable invitation and a pending one are the same thing")
        void redeemableAndPendingAgree() {
            // Two methods answering the same question from the same fields; a
            // list that showed PENDING while redemption refused would be a
            // support call nobody could explain.
            Invitation live = pending();

            assertThat(live.isRedeemable(NOW)).isTrue();
            assertThat(live.describeStatus(NOW)).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("anything not pending is not redeemable either")
        void nonPendingIsNotRedeemable() {
            for (Invitation each : java.util.List.of(
                    invitation(NOW.plusDays(7), NOW.minusHours(1), null),
                    invitation(NOW.plusDays(7), null, NOW.minusHours(1)),
                    invitation(NOW.minusMinutes(1), null, null))) {
                assertThat(each.describeStatus(NOW)).isNotEqualTo("PENDING");
                assertThat(each.isRedeemable(NOW))
                    .as("status %s must not be redeemable", each.describeStatus(NOW))
                    .isFalse();
            }
        }
    }
}
