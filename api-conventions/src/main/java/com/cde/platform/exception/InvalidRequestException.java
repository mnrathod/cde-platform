package com.cde.platform.exception;

/**
 * Raised when a request is well-formed but asks for something that is not on
 * offer, and the caller can be told exactly what.
 *
 * <p>Distinct from an ordinary {@link IllegalArgumentException}, which the
 * handler answers with "The request could not be processed as submitted."
 * That reticence is right for an exception the JDK or a library threw — its
 * message may name a class, a field or a path — and wrong for one this code
 * composed on purpose. A caller told only that their request was unprocessable
 * has to guess which parameter, and §1.4 asks for what happened, why, and what
 * to do next.
 *
 * <p>So the contract is the other way round from {@link ResourceNotFoundException}:
 * the message here <em>is</em> for the caller, and whoever throws it owes them
 * a sentence naming the offending value and the permitted ones. It must still
 * reveal nothing a caller could not already see — the value they themselves
 * sent, and a fixed list, both qualify.
 */
public class InvalidRequestException extends RuntimeException {

    /**
     * @param detail written for the person reading the error: the value that
     *               was refused, and what would be accepted instead
     */
    public InvalidRequestException(String detail) {
        super(detail);
    }
}
