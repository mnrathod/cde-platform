package com.cde.platform.cde.domain;

/**
 * Raised when a revision that has already been replaced is offered for
 * supersession a second time.
 *
 * <p>Its own exception rather than a generic illegal-state signal, because the
 * two are not equally meaningful: this one is an ordinary collision between two
 * people working on the same container, answerable with a conflict and a
 * message naming the revision that won. A generic illegal state is a bug, and
 * conflating them would report every future bug in this service as the caller's
 * fault.
 */
public class RevisionAlreadySupersededException extends RuntimeException {

    public RevisionAlreadySupersededException(String revisionCode, String supersededByCode) {
        super("Revision %s has already been superseded by revision %s. Issue the next revision "
              + "from that one instead.".formatted(revisionCode, supersededByCode));
    }
}
