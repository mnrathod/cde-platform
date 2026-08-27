package com.cde.platform.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What happens when sign-ins keep failing.
 *
 * <p>Unit tests, because the properties worth pinning are properties of the
 * counting rather than of the endpoint: which counter trips, what resets it,
 * and — the one most easily got wrong — what a success does <em>not</em> reset.
 */
class AuthenticationThrottleTest {

    private static final String ACCOUNT = "j.okafor";
    private static final String SOURCE = "203.0.113.17";

    private final AuthenticationThrottle throttle = new AuthenticationThrottle();

    private void fail(int times) {
        for (int attempt = 0; attempt < times; attempt++) {
            throttle.recordFailure(ACCOUNT, SOURCE);
        }
    }

    @Test
    @DisplayName("a few mistyped passwords are not penalised")
    void allowsAHandfulOfMistakes() {
        fail(4);

        // Someone with a sticky keyboard is the common case and an attacker is
        // the rare one. Penalising the first four attempts costs the common
        // case something real and the rare case nothing.
        assertThat(throttle.evaluate(ACCOUNT, SOURCE).isThrottled()).isFalse();
    }

    @Test
    @DisplayName("sustained failures start costing time")
    void throttlesAfterTheFreeAttempts() {
        fail(6);

        var decision = throttle.evaluate(ACCOUNT, SOURCE);

        assertThat(decision.isThrottled()).isTrue();
        assertThat(decision.retryAfterSeconds()).isPositive();
    }

    @Test
    @DisplayName("the delay grows with the number of failures")
    void delayIsProgressive() {
        fail(6);
        long afterSix = throttle.evaluate(ACCOUNT, SOURCE).retryAfterSeconds();

        fail(4);
        long afterTen = throttle.evaluate(ACCOUNT, SOURCE).retryAfterSeconds();

        // The property that makes this work: an attacker's rate collapses,
        // rather than being reduced by a constant.
        assertThat(afterTen).isGreaterThan(afterSix);
    }

    @Test
    @DisplayName("the delay stops growing rather than growing forever")
    void delayIsBounded() {
        fail(200);

        // Past a point, holding a request open is a way of exhausting the
        // server rather than the attacker.
        assertThat(throttle.evaluate(ACCOUNT, SOURCE).retryAfterSeconds())
            .isLessThanOrEqualTo(64);
    }

    @Test
    @DisplayName("signing in successfully clears the account's penalty")
    void successClearsTheAccountCounter() {
        fail(6);
        assertThat(throttle.evaluate(ACCOUNT, "198.51.100.9").isThrottled()).isTrue();

        throttle.recordSuccess(ACCOUNT);

        // From a different address, so only the account counter is in play.
        assertThat(throttle.evaluate(ACCOUNT, "198.51.100.9").isThrottled()).isFalse();
    }

    @Test
    @DisplayName("a success does not clear the source's penalty")
    void successDoesNotClearTheSourceCounter() {
        fail(6);
        throttle.recordSuccess(ACCOUNT);

        // The subtle one. If a success reset the source counter, an attacker
        // working through a credential list would simply interleave a login to
        // an account they already own and reset the penalty every few
        // attempts.
        assertThat(throttle.evaluate("someone.else", SOURCE).isThrottled()).isTrue();
    }

    @Test
    @DisplayName("one account's failures do not penalise another")
    void countersAreIndependentByAccount() {
        for (int attempt = 0; attempt < 6; attempt++) {
            throttle.recordFailure(ACCOUNT, "198.51.100." + attempt);
        }

        assertThat(throttle.evaluate("someone.else", "198.51.100.200").isThrottled()).isFalse();
    }

    @Test
    @DisplayName("a distributed attack on one account still trips the account counter")
    void catchesDistributedAttacks() {
        // Each attempt from a different address, so no source counter ever
        // reaches the threshold. Counting per account is what catches this.
        for (int attempt = 0; attempt < 8; attempt++) {
            throttle.recordFailure(ACCOUNT, "198.51.100." + attempt);
        }

        assertThat(throttle.evaluate(ACCOUNT, "198.51.100.99").isThrottled()).isTrue();
    }

    @Test
    @DisplayName("one host working through many accounts still trips the source counter")
    void catchesCredentialStuffing() {
        // Each attempt against a different account, so no account counter ever
        // reaches the threshold. Counting per source is what catches this —
        // and it is the shape a breach-list replay actually takes.
        for (int attempt = 0; attempt < 8; attempt++) {
            throttle.recordFailure("victim-" + attempt, SOURCE);
        }

        assertThat(throttle.evaluate("victim-99", SOURCE).isThrottled()).isTrue();
    }

    @Test
    @DisplayName("an account name and an address of the same text do not share a counter")
    void namespacesTheTwoCounters() {
        for (int attempt = 0; attempt < 8; attempt++) {
            throttle.recordFailure(SOURCE, "198.51.100." + attempt);
        }

        // The account is literally called "203.0.113.17". Its failures must not
        // penalise requests arriving from that address.
        assertThat(throttle.evaluate("unrelated", SOURCE).isThrottled()).isFalse();
    }

    @Test
    @DisplayName("Retry-After is never zero seconds")
    void retryAfterAlwaysAsksForARealWait() {
        fail(6);

        // A Retry-After of 0 invites an immediate retry, which is the opposite
        // of what the header is for.
        assertThat(throttle.evaluate(ACCOUNT, SOURCE).retryAfterSeconds())
            .isGreaterThanOrEqualTo(1);
    }
}
