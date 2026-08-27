package com.cde.platform.mfa;

/** The operation needs an enrolment and there is none. */
public class MfaNotEnrolledException extends RuntimeException {

    public MfaNotEnrolledException(long userId) {
        super("User " + userId + " has no multi-factor enrolment");
    }
}
