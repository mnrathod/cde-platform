package com.cde.platform.exception;

/**
 * Raised when a request cannot proceed because it collides with something that
 * already exists.
 *
 * <p>Carries a message written for the caller. That is the difference between
 * this and letting the database constraint surface as a generic conflict: the
 * constraint knows an index was violated, and the caller needs to know which of
 * their values was the duplicate.
 *
 * <p>Raising it does not replace the constraint. Two concurrent requests can
 * both pass the check and only one can pass the index, so the constraint stays
 * as the thing that is actually true.
 */
public class ResourceConflictException extends RuntimeException {

    public ResourceConflictException(String message) {
        super(message);
    }
}
