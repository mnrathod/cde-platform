package com.cde.platform.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hash that makes the trail tamper-evident.
 *
 * <p>Unit tests, no Spring and no database: this is a pure function, and the
 * properties worth pinning are properties of the encoding rather than of any
 * particular stored record.
 */
class AuditRecordHashTest {

    private static final OffsetDateTime WHEN =
        OffsetDateTime.of(2026, 8, 27, 9, 15, 4, 123_456_789, ZoneOffset.UTC);

    private String hashWith(String actorLabel, AuditOutcome outcome) {
        return AuditRecordHash.of(AuditRecordHash.GENESIS_HASH, 7L, 1L, WHEN,
            AuditAction.SIGN_IN, outcome, 41L, actorLabel,
            "203.0.113.17", "Mozilla/5.0", "User", "41", "trace-1", null);
    }

    @Test
    @DisplayName("the same record hashes the same way twice")
    void isDeterministic() {
        assertThat(hashWith("j.okafor", AuditOutcome.SUCCESS))
            .isEqualTo(hashWith("j.okafor", AuditOutcome.SUCCESS));
    }

    @Test
    @DisplayName("changing any field changes the hash")
    void detectsAnAlteredField() {
        assertThat(hashWith("j.okafor", AuditOutcome.SUCCESS))
            .isNotEqualTo(hashWith("j.okafor", AuditOutcome.DENIED));
    }

    @Test
    @DisplayName("a field cannot be rewritten by moving a delimiter into it")
    void isUnambiguousAcrossFieldBoundaries() {
        // The reason the encoding is length-prefixed rather than joined with a
        // separator. With a separator, an actor named so as to contain one
        // could be re-parsed as a different actor plus a different next field,
        // and the two records would hash identically — so an attacker who
        // could choose a username could produce a record that verified as
        // something else. Usernames and user agents are exactly the
        // attacker-influenced fields in here.
        String withSeparatorInTheName = hashWith("j.okafor;8:DENIED;", AuditOutcome.SUCCESS);
        String withoutIt = hashWith("j.okafor", AuditOutcome.DENIED);

        assertThat(withSeparatorInTheName).isNotEqualTo(withoutIt);
    }

    @Test
    @DisplayName("an absent field and an empty one hash differently")
    void distinguishesNullFromEmpty() {
        String absent = AuditRecordHash.of(AuditRecordHash.GENESIS_HASH, 7L, 1L, WHEN,
            AuditAction.SIGN_IN, AuditOutcome.SUCCESS, 41L, "j.okafor",
            null, null, null, null, null, null);
        String empty = AuditRecordHash.of(AuditRecordHash.GENESIS_HASH, 7L, 1L, WHEN,
            AuditAction.SIGN_IN, AuditOutcome.SUCCESS, 41L, "j.okafor",
            "", "", "", "", "", "");

        assertThat(absent).isNotEqualTo(empty);
    }

    @Test
    @DisplayName("the same instant in another offset hashes the same")
    void doesNotDependOnWhereItWasComputed() {
        OffsetDateTime elsewhere = WHEN.withOffsetSameInstant(ZoneOffset.ofHours(10));

        String inUtc = AuditRecordHash.of(AuditRecordHash.GENESIS_HASH, 7L, 1L, WHEN,
            AuditAction.SIGN_IN, AuditOutcome.SUCCESS, 41L, "j.okafor",
            null, null, null, null, null, null);
        String inSydney = AuditRecordHash.of(AuditRecordHash.GENESIS_HASH, 7L, 1L, elsewhere,
            AuditAction.SIGN_IN, AuditOutcome.SUCCESS, 41L, "j.okafor",
            null, null, null, null, null, null);

        // A record written by an instance in one region must verify against one
        // in another. Rendering the timestamp in the local offset would make
        // the chain depend on which pod happened to serve the request.
        assertThat(inUtc).isEqualTo(inSydney);
    }

    @Test
    @DisplayName("the genesis hash is the hash of the empty string")
    void genesisIsWellKnown() {
        // So that an outside verifier — a customer's SIEM — can start the chain
        // without being told a magic value.
        assertThat(AuditRecordHash.GENESIS_HASH)
            .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }
}
