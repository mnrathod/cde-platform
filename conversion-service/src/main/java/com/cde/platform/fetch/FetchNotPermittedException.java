package com.cde.platform.fetch;

/**
 * A URL an integrator supplied may not be fetched.
 *
 * <p>Carries a message written for the person integrating: what was refused
 * and what to do about it (§1.4). It deliberately never names the address a
 * host resolved to — a caller who learns that {@code probe.example} reached
 * {@code 10.0.4.17} has been handed a network map one refusal at a time.
 */
public class FetchNotPermittedException extends RuntimeException {

    public FetchNotPermittedException(String message) {
        super(message);
    }
}
