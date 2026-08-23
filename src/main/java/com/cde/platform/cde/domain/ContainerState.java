package com.cde.platform.cde.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * The four states of an information container, per ISO 19650-1.
 *
 * <p>This is a state machine, not a status column. The difference is the whole
 * point: a status field can be set to any value by anything that can write the
 * row, whereas a state can only be reached by a transition that carries its own
 * permission, validation, approval record and audit event. On a contractual
 * record — which is what a published container is — "who moved this, when, and
 * on whose authority" has to be answerable years later.
 *
 * <p>No framework imports here, deliberately. The rules about which move is
 * legal belong to the domain and are exercised by fast unit tests with no
 * database, no Spring context and nothing to mock.
 */
public enum ContainerState {

    /**
     * Unverified, owned by one task team and visible only to them.
     * Leaving requires check, review and approval within that team.
     */
    WORK_IN_PROGRESS,

    /**
     * Issued for coordination and reference, visible to other task teams on the
     * project. Explicitly <em>not</em> approved for use — sharing is not
     * publication, and conflating the two is how unapproved information ends up
     * built.
     */
    SHARED,

    /**
     * Authorised for use: the contractual record. Immutable. A change produces
     * a new revision that supersedes this one; this one remains retrievable for
     * as long as the asset exists.
     */
    PUBLISHED,

    /**
     * Superseded or completed, retained as the historical record. Terminal —
     * there is no transition out, by design.
     */
    ARCHIVED;

    /**
     * @return the states reachable from this one. Empty for terminal states.
     */
    public Set<ContainerState> permittedTransitions() {
        return switch (this) {
            case WORK_IN_PROGRESS -> EnumSet.of(SHARED, ARCHIVED);
            // Back to WIP is a rejection: a reviewer returning work for rework.
            // Straight to PUBLISHED is the authorisation by the lead appointed
            // party.
            case SHARED -> EnumSet.of(PUBLISHED, WORK_IN_PROGRESS, ARCHIVED);
            // Publication is one-way. A published container is never edited and
            // never withdrawn — it is superseded by a new revision, and then
            // archived.
            case PUBLISHED -> EnumSet.of(ARCHIVED);
            case ARCHIVED -> EnumSet.noneOf(ContainerState.class);
        };
    }

    public boolean canTransitionTo(ContainerState target) {
        return permittedTransitions().contains(target);
    }

    /**
     * Whether the container's content may still be modified in place.
     *
     * <p>False from publication onward. This is asserted by a database trigger
     * as well, because an application-level check is only as good as every
     * future call site remembering to consult it.
     */
    public boolean isMutable() {
        return this == WORK_IN_PROGRESS || this == SHARED;
    }

    public boolean isTerminal() {
        return permittedTransitions().isEmpty();
    }

    /**
     * Whether reaching this state requires a recorded authorisation — an
     * approver and a reason — rather than merely a permitted actor.
     */
    public boolean requiresRecordedApproval() {
        return this == PUBLISHED;
    }

    /**
     * The permission a caller must hold to move a container <em>into</em> this
     * state. Server-side enforcement is elsewhere; this is the single place the
     * mapping is defined, so no endpoint can invent its own.
     */
    public String requiredPermission() {
        return switch (this) {
            case WORK_IN_PROGRESS -> ContainerPermission.REJECT;
            case SHARED -> ContainerPermission.SHARE;
            case PUBLISHED -> ContainerPermission.PUBLISH;
            case ARCHIVED -> ContainerPermission.ARCHIVE;
        };
    }
}
