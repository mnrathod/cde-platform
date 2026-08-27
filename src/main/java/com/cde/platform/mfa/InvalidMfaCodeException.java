package com.cde.platform.mfa;

/**
 * The presented code was wrong, expired, or already spent.
 *
 * <p>One exception for all three deliberately. Distinguishing "wrong code"
 * from "already used" would tell an attacker holding an observed code that it
 * was genuine and merely stale, which narrows their next attempt.
 */
public class InvalidMfaCodeException extends RuntimeException {

    public InvalidMfaCodeException() {
        super("That verification code is not valid");
    }
}
