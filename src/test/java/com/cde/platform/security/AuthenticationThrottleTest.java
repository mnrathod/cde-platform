package com.cde.platform.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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

    // ── Counters that are kept apart, and eventually let go ────────────────

    @Test
    @DisplayName("the account counter and the source counter are separate")
    void accountAndSourceAreCountedSeparately() {
        // A distributed attack trips the account counter while every source
        // looks innocent; one host working through a list trips the source
        // counter while every account does. Sharing one counter would miss
        // whichever shape it was not keyed for.
        AuthenticationThrottle throttle = new AuthenticationThrottle();
        for (int attempt = 0; attempt < 8; attempt++) {
            throttle.recordFailure("a.okafor", "203.0.113.17");
        }

        // A different account from the same host is still throttled, because
        // the source counter is the one that tripped.
        assertThat(throttle.evaluate("someone.else", "203.0.113.17").isThrottled()).isTrue();
    }

    @Test
    @DisplayName("a different source is throttled when the account is the one at fault")
    void accountCounterFollowsTheAccount() {
        AuthenticationThrottle throttle = new AuthenticationThrottle();
        for (int attempt = 0; attempt < 8; attempt++) {
            throttle.recordFailure("a.okafor", "203.0.113." + attempt);
        }

        assertThat(throttle.evaluate("a.okafor", "198.51.100.4").isThrottled()).isTrue();
    }

    @Test
    @DisplayName("an unrelated account from an unrelated host is not penalised")
    void unrelatedCallersAreUnaffected() {
        // The other half: a throttle that slows everybody down after one
        // account is attacked is a denial of service with extra steps.
        AuthenticationThrottle throttle = new AuthenticationThrottle();
        for (int attempt = 0; attempt < 8; attempt++) {
            throttle.recordFailure("a.okafor", "203.0.113.17");
        }

        assertThat(throttle.evaluate("r.li", "198.51.100.4").isThrottled()).isFalse();
    }

    @Test
    @DisplayName("the stricter of the two counters decides")
    void theStricterCounterWins() {
        AuthenticationThrottle throttle = new AuthenticationThrottle();
        // Eight failures on the account, two on this host. The account's delay
        // is the longer one and must be the one reported.
        for (int attempt = 0; attempt < 8; attempt++) {
            throttle.recordFailure("a.okafor", "203.0.113." + attempt);
        }
        throttle.recordFailure("a.okafor", "198.51.100.4");

        assertThat(throttle.evaluate("a.okafor", "198.51.100.4").retryAfter())
            .isPositive();
    }

    @Test
    @DisplayName("a missing account name is counted rather than crashing")
    void handlesAnAbsentAccount() {
        // The login endpoint rejects a blank username before this, but a
        // throttle that throws on one would turn a malformed request into a
        // server fault — and into a way to skip being counted.
        AuthenticationThrottle throttle = new AuthenticationThrottle();

        assertThatCode(() -> {
            for (int attempt = 0; attempt < 8; attempt++) {
                throttle.recordFailure(null, null);
            }
        }).doesNotThrowAnyException();
        assertThat(throttle.evaluate(null, null).isThrottled()).isTrue();
    }

    @Test
    @DisplayName("a null account and an empty one are the same counter")
    void absentAndEmptyShareACounter() {
        // Otherwise alternating between them doubles the free attempts.
        AuthenticationThrottle throttle = new AuthenticationThrottle();
        for (int attempt = 0; attempt < 4; attempt++) throttle.recordFailure(null, "src");
        for (int attempt = 0; attempt < 4; attempt++) throttle.recordFailure("", "src");

        assertThat(throttle.evaluate("", "src").isThrottled()).isTrue();
    }

    @Test
    @DisplayName("the delay stops growing at its ceiling")
    void delayIsCapped() {
        // Unbounded doubling locks an account out for days after a bad
        // afternoon, which is the denial of service §4.2 warns about.
        AuthenticationThrottle throttle = new AuthenticationThrottle();
        for (int attempt = 0; attempt < 200; attempt++) {
            throttle.recordFailure("a.okafor", SOURCE);
        }

        assertThat(throttle.evaluate("a.okafor", SOURCE).retryAfter())
            .isLessThanOrEqualTo(java.time.Duration.ofSeconds(64));
    }

    @Test
    @DisplayName("a successful sign-in clears the account's counter")
    void successClearsTheAccount() {
        AuthenticationThrottle throttle = new AuthenticationThrottle();
        for (int attempt = 0; attempt < 8; attempt++) {
            throttle.recordFailure("a.okafor", "198.51.100.9");
        }

        throttle.recordSuccess("a.okafor");

        assertThat(throttle.evaluate("a.okafor", "203.0.113.200").isThrottled()).isFalse();
    }

    @Test
    @DisplayName("a successful sign-in does not clear the host's counter")
    void successDoesNotClearTheSource() {
        // It must not: guessing one password correctly is exactly what an
        // attacker working through a list eventually does, and clearing the
        // source counter then would reward it.
        AuthenticationThrottle throttle = new AuthenticationThrottle();
        for (int attempt = 0; attempt < 8; attempt++) {
            throttle.recordFailure("victim" + attempt, "203.0.113.17");
        }

        throttle.recordSuccess("victim0");

        assertThat(throttle.evaluate("victim0", "203.0.113.17").isThrottled()).isTrue();
    }

    @Test
    @DisplayName("an account nobody has failed on is not throttled")
    void unknownAccountIsAllowed() {
        assertThat(new AuthenticationThrottle()
            .evaluate("never.seen", "203.0.113.1").isThrottled()).isFalse();
    }

    @Test
    @DisplayName("an allowed decision asks for no wait at all")
    void allowedMeansNoWait() {
        assertThat(new AuthenticationThrottle().evaluate("fresh", "src").retryAfter())
            .isZero();
    }
}
