package com.cde.platform.audit;

import java.util.Map;

/**
 * Reads an {@link AuditableChange} back, for tests outside this package.
 *
 * <p>{@code AuditableChange.fields()} is package-private deliberately: the
 * summary is written once, into the audit trail, and nothing in the
 * application has any business reading it back. That is the right shape for
 * production code and the wrong one for a test that needs to assert what did
 * <em>not</em> reach the record — which is the assertion §10.1 and §5.7 both
 * turn on, since the rule is not "log the call" but "log the call and never
 * the payload".
 *
 * <p>So the accessor is widened here, in the test tree, rather than on the
 * class itself. A public getter on {@code AuditableChange} would invite a
 * caller to read a change summary back into application logic, and a
 * {@code toString} would invite it into a log line — either of which puts the
 * summary somewhere it was never meant to go.
 */
public final class RecordedChanges {

    private RecordedChanges() {
    }

    /** The fields a change carries, in the order they were added. */
    public static Map<String, String> fieldsOf(AuditableChange change) {
        return change.fields();
    }

    /** Every field's name and value as one string, for a contains assertion. */
    public static String flatten(AuditableChange change) {
        return change.fields().entrySet().stream()
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .reduce((left, right) -> left + " " + right)
            .orElse("");
    }
}
