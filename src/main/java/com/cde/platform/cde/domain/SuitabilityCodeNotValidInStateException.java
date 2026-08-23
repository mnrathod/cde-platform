package com.cde.platform.cde.domain;

/**
 * Raised when a suitability code is applied to a revision in a state the code
 * is not permitted in.
 *
 * <p>The message names the code and both states, and nothing else. Like
 * {@link StateTransitionNotPermittedException} it is safe to show a user: the
 * code is one their own project defined, and knowing which state it belongs to
 * is what tells them what to do instead.
 */
public class SuitabilityCodeNotValidInStateException extends RuntimeException {

    private final String code;

    public SuitabilityCodeNotValidInStateException(String code,
                                                   ContainerState validIn,
                                                   ContainerState actual) {
        super("Suitability code %s applies to information that is %s. This revision is %s."
            .formatted(code, readable(validIn), readable(actual)));
        this.code = code;
    }

    private static String readable(ContainerState state) {
        return state.name().toLowerCase().replace('_', ' ');
    }

    public String getCode() {
        return code;
    }
}
