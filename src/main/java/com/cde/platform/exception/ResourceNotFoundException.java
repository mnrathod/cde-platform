package com.cde.platform.exception;

/**
 * Raised when a request names something the caller cannot see.
 *
 * <p>Deliberately does not distinguish "no such row" from "a row another tenant
 * owns". Both are reported as {@code 404} with the same body, because a
 * {@code 403} on someone else's identifier confirms that the identifier exists
 * — which is the whole of what an enumeration attack is trying to learn.
 */
public class ResourceNotFoundException extends RuntimeException {

    /**
     * @param detail what was not found, phrased without confirming what does
     *               exist — "No such project", not "Project 42 belongs to
     *               another organisation"
     */
    public ResourceNotFoundException(String detail) {
        super(detail);
    }
}
