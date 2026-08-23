package com.cde.platform.cde.domain;

/**
 * Raised when a caller attempts a state change the machine does not allow.
 *
 * <p>The message names the states involved and nothing else. It is safe to show
 * a user — it discloses no internal detail — and it is the only thing they need
 * to understand what happened.
 */
public class StateTransitionNotPermittedException extends RuntimeException {

    private final ContainerState from;
    private final ContainerState to;

    public StateTransitionNotPermittedException(ContainerState from, ContainerState to) {
        super(describe(from, to));
        this.from = from;
        this.to = to;
    }

    private static String describe(ContainerState from, ContainerState to) {
        if (from == to) {
            return "The container is already " + readable(from) + ".";
        }
        if (from.isTerminal()) {
            return "A container that is " + readable(from) + " cannot change state. "
                 + "It is the historical record.";
        }
        if (from == ContainerState.PUBLISHED && to != ContainerState.ARCHIVED) {
            return "A published container cannot return to " + readable(to)
                 + ". Publication is the contractual record: issue a new revision "
                 + "that supersedes this one instead.";
        }
        return "A container that is " + readable(from) + " cannot move directly to "
             + readable(to) + ".";
    }

    private static String readable(ContainerState state) {
        return state.name().toLowerCase().replace('_', ' ');
    }

    public ContainerState getFrom() {
        return from;
    }

    public ContainerState getTo() {
        return to;
    }
}
