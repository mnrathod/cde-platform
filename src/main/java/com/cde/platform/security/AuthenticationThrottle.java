package com.cde.platform.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Slows down repeated failed sign-ins.
 *
 * <p>Nothing did. An unthrottled login endpoint is a credential-stuffing
 * endpoint: a breach list can be replayed against it as fast as the network
 * allows, and every reused password in it is found.
 *
 * <p><strong>Progressive delay rather than account lockout</strong>, which is
 * the choice §4.2 makes and worth restating because it looks like the weaker
 * option. Locking an account on failed attempts hands anybody who knows a
 * username the ability to lock its owner out — the control becomes the attack.
 * Delay costs an attacker the thing they need (rate) and costs a legitimate
 * user who mistyped a password almost nothing.
 *
 * <p>Counted per account <em>and</em> per source address, independently. Per
 * account alone misses one attempt against each of ten thousand accounts; per
 * address alone misses a distributed run against one account.
 *
 * <p><strong>This is per instance.</strong> Counters live in this process, so a
 * deployment running four replicas throttles at four times the configured rate
 * — the limit is real but looser than it reads. Moving the counters to the
 * shared cache is the fix and is an implementation change behind this class,
 * not a redesign. Until then, a deployment that needs a hard bound should set
 * one at the edge as well.
 */
@Component
public class AuthenticationThrottle {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationThrottle.class);

    /**
     * Attempts allowed before any delay applies. Generous, because the cost of
     * being wrong here is a locked-out user with a sticky keyboard, and the
     * delay grows fast enough afterwards that generosity costs an attacker
     * nothing.
     */
    private static final int FREE_ATTEMPTS = 5;

    /** Doubling from here: 1s, 2s, 4s… */
    private static final Duration BASE_DELAY = Duration.ofSeconds(1);

    /**
     * The point at which delay becomes refusal. Sixty-four seconds of enforced
     * waiting per attempt has already destroyed the attempt rate; beyond it,
     * holding a request thread open is a way of exhausting the server rather
     * than the attacker.
     */
    private static final Duration MAX_DELAY = Duration.ofSeconds(64);

    /**
     * How long a quiet counter survives. Long enough that an attacker cannot
     * wait out the penalty cheaply, short enough that a user who mistyped their
     * password at nine in the morning is not still penalised after lunch.
     */
    private static final Duration COUNTER_LIFETIME = Duration.ofMinutes(30);

    /**
     * Bounded so the map itself cannot be the attack: without a cap, an
     * attacker inventing a username per request fills the heap.
     */
    private static final int MAX_TRACKED_KEYS = 100_000;

    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    /**
     * @param account the username as claimed, which may not exist — deliberately
     *                counted anyway, so that probing for valid usernames is
     *                throttled at the same rate as guessing passwords
     * @param sourceAddress where the request came from
     * @return how long the caller must wait, or {@link Decision#allowed()}
     */
    public Decision evaluate(String account, String sourceAddress) {
        Decision byAccount = decisionFor(key("account", account));
        Decision bySource = decisionFor(key("source", sourceAddress));

        // The stricter of the two. A distributed attack trips the account
        // counter; a single host working through a list trips the source one.
        return byAccount.retryAfter().compareTo(bySource.retryAfter()) >= 0
            ? byAccount : bySource;
    }

    /** Records a failure against both counters. */
    public void recordFailure(String account, String sourceAddress) {
        increment(key("account", account));
        increment(key("source", sourceAddress));
    }

    /**
     * Clears the account's counter on a successful sign-in.
     *
     * <p>The source counter is deliberately left alone. One success does not
     * establish that a host working through a list of credentials is
     * legitimate — if it did, an attacker would simply interleave a known-good
     * login to reset the penalty.
     */
    public void recordSuccess(String account) {
        attempts.remove(key("account", account));
    }

    private Decision decisionFor(String key) {
        Attempts tracked = attempts.get(key);
        if (tracked == null || tracked.hasExpired()) {
            return Decision.allowed();
        }
        int failures = tracked.count.get();
        if (failures < FREE_ATTEMPTS) {
            return Decision.allowed();
        }

        Duration delay = delayFor(failures);
        Duration waited = Duration.between(tracked.lastFailure, Instant.now());
        if (waited.compareTo(delay) >= 0) {
            return Decision.allowed();
        }
        return Decision.wait(delay.minus(waited));
    }

    private Duration delayFor(int failures) {
        int doublings = Math.min(failures - FREE_ATTEMPTS, 6);
        Duration delay = BASE_DELAY.multipliedBy(1L << doublings);
        return delay.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : delay;
    }

    private void increment(String key) {
        if (attempts.size() >= MAX_TRACKED_KEYS) {
            purgeExpired();
        }
        if (attempts.size() >= MAX_TRACKED_KEYS) {
            // Still full after purging: the map is under active pressure.
            // Dropping the new key rather than growing is the right failure —
            // it loses throttling for one identifier and keeps the process
            // alive, where growing loses both.
            log.warn("The authentication throttle is at capacity; "
                   + "this attempt is not being counted.");
            return;
        }
        attempts.compute(key, (ignored, existing) -> {
            if (existing == null || existing.hasExpired()) {
                return new Attempts();
            }
            existing.record();
            return existing;
        });
    }

    private void purgeExpired() {
        attempts.values().removeIf(Attempts::hasExpired);
    }

    /**
     * Namespaced so an account called {@code 10.0.0.1} cannot share a counter
     * with the address of the same name.
     */
    private String key(String kind, String value) {
        return kind + ':' + (value == null ? "" : value);
    }

    private static final class Attempts {

        private final AtomicInteger count = new AtomicInteger(1);
        private volatile Instant lastFailure = Instant.now();

        void record() {
            count.incrementAndGet();
            lastFailure = Instant.now();
        }

        boolean hasExpired() {
            return Duration.between(lastFailure, Instant.now()).compareTo(COUNTER_LIFETIME) > 0;
        }
    }

    /**
     * @param retryAfter how long to wait; zero means proceed
     */
    public record Decision(Duration retryAfter) {

        static Decision allowed() {
            return new Decision(Duration.ZERO);
        }

        static Decision wait(Duration retryAfter) {
            return new Decision(retryAfter);
        }

        public boolean isThrottled() {
            return !retryAfter.isZero() && !retryAfter.isNegative();
        }

        /** Rounded up: a Retry-After of zero seconds invites an immediate retry. */
        public long retryAfterSeconds() {
            return Math.max(1, (retryAfter.toMillis() + 999) / 1000);
        }
    }
}
