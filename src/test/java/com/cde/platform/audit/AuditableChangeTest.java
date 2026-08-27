package com.cde.platform.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The guard on what may be written into the audit trail.
 *
 * <p>This matters more than it looks. The trail is the log most likely to be
 * exported to a customer's SIEM, handed to an auditor, and kept for seven
 * years — so a credential written here by mistake is a credential in all three
 * places, and it is the one log nobody can go back and redact, because the
 * application has no UPDATE on the table.
 */
class AuditableChangeTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "password", "newPassword", "password_hash", "PASSWORD",
        "apiKey", "api_key", "sessionId", "authorization",
        "totpSecret", "recovery_code", "cardNumber"})
    @DisplayName("a field whose name looks like a secret is refused")
    void refusesSecretLookingFields(String field) {
        assertThatThrownBy(() -> AuditableChange.of(field, "anything"))
            .isInstanceOf(IllegalArgumentException.class)
            // The message has to say what to do instead, because the person
            // reading it is trying to audit something and needs a way to.
            .hasMessageContaining("Record an identifier or a boolean");
    }

    @Test
    @DisplayName("it throws rather than dropping the field quietly")
    void refusesLoudly() {
        // A silently dropped field teaches nobody: the caller believes the
        // value is being recorded, the trail is missing it, and the next
        // person writes the same line again.
        assertThatThrownBy(() -> AuditableChange.of("userPassword", "hunter2"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ordinary fields are kept")
    void keepsOrdinaryFields() {
        var change = AuditableChange.of("state", "PUBLISHED").and("revisionCode", "P01.01");

        assertThat(change.fields())
            .containsEntry("state", "PUBLISHED")
            .containsEntry("revisionCode", "P01.01");
    }

    @Test
    @DisplayName("a field can record that something changed without recording what")
    void supportsRedactedFields() {
        var change = AuditableChange.none().andRedacted("password");

        // The escape hatch: "the password was changed" is exactly the event an
        // audit trail should carry, and neither value belongs in it.
        assertThat(change.fields()).containsEntry("password", "[redacted]");
    }

    @Test
    @DisplayName("a cleared value is distinguishable from the text 'null'")
    void keepsNullsDistinct() {
        var change = AuditableChange.of("location", null);

        assertThat(change.fields()).containsEntry("location", null);
    }

    @Test
    @DisplayName("a value too long to be a summary is refused")
    void refusesOversizedValues() {
        assertThatThrownBy(() -> AuditableChange.of("body", "x".repeat(513)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Record a reference rather than the content");
    }

    @Test
    @DisplayName("a record cannot become a copy of the request")
    void refusesTooManyFields() {
        var change = AuditableChange.none();
        for (int field = 0; field < 32; field++) {
            change.and("field" + field, field);
        }

        assertThatThrownBy(() -> change.and("oneTooMany", "value"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at most 32");
    }
}
