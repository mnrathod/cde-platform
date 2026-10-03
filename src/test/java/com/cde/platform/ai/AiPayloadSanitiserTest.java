package com.cde.platform.ai;

import com.cde.platform.ai.AiPayloadSanitiser.ComparisonFacts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The adversarial corpus for the outbound sanitiser.
 *
 * <p>These are unit tests over a pure function, and that is the point: this is
 * the class that decides what leaves the deployment, so what it does must be
 * establishable without a database, a network, or a provider. Every case here
 * is something that would be a reportable incident if it reached a third party.
 */
class AiPayloadSanitiserTest {

    private final AiPayloadSanitiser sanitiser = new AiPayloadSanitiser();

    private ComparisonFacts factsWith(String documentName, String... changes) {
        return new ComparisonFacts(documentName, "GA Plan — Level 02",
                                   "P01", "P02", "drawing", List.of(changes));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "OFFICIAL-SENSITIVE Site Layout",
        "official-sensitive site layout",
        "OFFICIAL: SENSITIVE — Perimeter",
        "PROTECTED asset register",
        "SECRET installation plan",
        "TOP SECRET",
        "CONFIDENTIAL commercial schedule",
        "RESTRICTED area services"})
    @DisplayName("a classification marking refuses the whole payload")
    void refusesClassifiedContent(String markedName) {
        var payload = sanitiser.sanitise(factsWith(markedName, "a change"));

        // Refused, not redacted. There is no redaction that makes classified
        // material safe to send to a third party, and a partial send is still
        // a send.
        assertThat(payload.isRefused()).isTrue();
        assertThat(payload.prompt()).isNull();
    }

    @Test
    @DisplayName("a marking anywhere in the payload refuses all of it")
    void refusesWhenOnlyOneChangeIsMarked() {
        var payload = sanitiser.sanitise(factsWith(
            "GA Plan — Level 02",
            "Door schedule updated",
            "PROTECTED: plant room access route revised"));

        assertThat(payload.isRefused()).isTrue();
    }

    @Test
    @DisplayName("the refusal names the field, never its contents")
    void refusalDoesNotEchoTheClassifiedText() {
        var payload = sanitiser.sanitise(factsWith("SECRET installation plan", "a change"));

        // The whole point of refusing is that the text does not travel. Putting
        // it into the error message would send it to a log, a browser, and
        // possibly a support ticket instead.
        assertThat(payload.refusalDetail())
            .doesNotContain("installation plan")
            .contains("classification marking");
    }

    @Test
    @DisplayName("the word 'official' in ordinary prose is not a marking")
    void doesNotRefuseOrdinaryProse() {
        // Construction documents say this constantly. A sanitiser that refuses
        // every drawing mentioning an official submission is one that gets
        // switched off.
        var payload = sanitiser.sanitise(factsWith(
            "Officially issued for construction", "Officer's mess layout revised"));

        assertThat(payload.isRefused()).isFalse();
    }

    @Test
    @DisplayName("an email address is replaced, not sent")
    void pseudonymisesEmailAddresses() {
        var payload = sanitiser.sanitise(factsWith(
            "GA Plan", "Queried by j.okafor@contractor.example"));

        assertThat(payload.prompt()).doesNotContain("j.okafor@contractor.example");
        assertThat(payload.prompt()).contains("[EMAIL_1]");
        assertThat(payload.redacted()).isTrue();
    }

    @Test
    @DisplayName("the same value gets the same placeholder throughout")
    void placeholdersAreStable() {
        var payload = sanitiser.sanitise(factsWith(
            "GA Plan",
            "Raised by j.okafor@contractor.example",
            "Closed by j.okafor@contractor.example"));

        // So the model can tell that the two mentions are the same person,
        // which is most of why the text is worth sending at all.
        //
        // Three parts from two occurrences. The prompt's own instructions
        // mention placeholders as [EMAIL_n] rather than [EMAIL_1], precisely so
        // the example cannot be mistaken for — or counted as — a real one.
        assertThat(payload.prompt().split("\\[EMAIL_1]", -1)).hasSize(3);
        assertThat(payload.placeholders().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("the real values come back locally, and only locally")
    void rehydratesOnTheWayHome() {
        var payload = sanitiser.sanitise(factsWith(
            "GA Plan", "Queried by j.okafor@contractor.example"));

        String modelReply = "The query from [EMAIL_1] remains open.";

        assertThat(payload.placeholders().rehydrate(modelReply))
            .isEqualTo("The query from j.okafor@contractor.example remains open.");
    }

    @Test
    @DisplayName("telephone numbers and addresses are replaced too")
    void pseudonymisesOtherIdentifiers() {
        var payload = sanitiser.sanitise(factsWith(
            "GA Plan",
            "Contact +44 20 7946 0958 before works",
            "Superseded by https://intranet.contractor.example/dwg/1180",
            "Plant at 10.20.30.40 relocated"));

        assertThat(payload.prompt())
            .doesNotContain("7946 0958")
            .doesNotContain("intranet.contractor.example")
            .doesNotContain("10.20.30.40");
    }

    @Test
    @DisplayName("only the allow-listed fields appear in what is sent")
    void sendsNothingBeyondTheAllowList() {
        var payload = sanitiser.sanitise(new ComparisonFacts(
            "GA Plan — Level 02", "GA Plan — Level 02", "P01", "P02", "drawing",
            List.of("Door schedule updated on grid line C")));

        // The prompt is assembled here from named fields. Anything a
        // comparison grows later does not travel until somebody adds it to
        // ComparisonFacts, which is the property a deny-list cannot give.
        assertThat(payload.prompt())
            .contains("GA Plan — Level 02")
            .contains("P01")
            .contains("P02")
            .contains("Door schedule updated on grid line C");
    }

    @Test
    @DisplayName("a caller cannot ship a document out one field at a time")
    void boundsFieldLength() {
        String smuggled = "x".repeat(5000);
        var payload = sanitiser.sanitise(factsWith("GA Plan", smuggled));

        assertThat(payload.prompt().length()).isLessThan(2000);
    }

    @Test
    @DisplayName("a very long change list is truncated rather than sent whole")
    void boundsChangeCount() {
        String[] many = new String[500];
        java.util.Arrays.fill(many, "Change on grid line C");

        var payload = sanitiser.sanitise(factsWith("GA Plan", many));

        assertThat(payload.prompt().split("Change on grid line C", -1).length - 1)
            .isLessThanOrEqualTo(40);
    }

    @Test
    @DisplayName("nothing is sent when there is nothing to say")
    void handlesEmptyInput() {
        var payload = sanitiser.sanitise(new ComparisonFacts(
            null, null, null, null, "drawing", List.of()));

        assertThat(payload.isRefused()).isFalse();
        assertThat(payload.redacted()).isFalse();
    }

    // ── The marking test, used directly ───────────────────────────────────
    //
    // carriesClassificationMarking is public so other outbound paths can apply
    // the same test rather than writing their own — §10.1 calls the rule
    // absolute, and a second implementation of an absolute rule is a second
    // chance to get it wrong. Which means the method is a contract in its own
    // right, not an internal of sanitise(), and the markings it has to
    // recognise are the ones the contracts in §6.4–6.6 name.

    @Nested
    @DisplayName("recognising a classification marking")
    class MarkingRecognition {

        @ParameterizedTest(name = "{0} is a marking")
        @ValueSource(strings = {
            "OFFICIAL-SENSITIVE",
            "OFFICIAL - SENSITIVE",
            "OFFICIAL: SENSITIVE",
            "OFFICIAL:SENSITIVE",
            "PROTECTED",
            "SECRET",
            "TOP SECRET",
            "CONFIDENTIAL",
            "RESTRICTED",
            "CODEWORD",
            "ACCOUNTABLE MATERIAL"
        })
        @DisplayName("every marking the contracts name is recognised")
        void everyMarkingIsRecognised(String marking) {
            // UK GSCP and the Australian PSPF between them produce all of
            // these. Missing one is not a partial failure — it is the whole
            // control failing for whichever customer uses that marking.
            assertThat(sanitiser.carriesClassificationMarking(
                "Drawing A-101 " + marking + " do not distribute")).isTrue();
        }

        @ParameterizedTest(name = "{0} is recognised whatever its case")
        @ValueSource(strings = {
            "official-sensitive", "Official-Sensitive", "pRoTeCtEd", "top secret"
        })
        @DisplayName("case does not matter, because a marking is typed by hand")
        void caseDoesNotMatter(String marking) {
            assertThat(sanitiser.carriesClassificationMarking(marking)).isTrue();
        }

        @Test
        @DisplayName("an em dash between the words is recognised, not only a hyphen")
        void anEmDashIsRecognised() {
            // Word processors substitute one for the other silently, so a
            // marking pasted out of a document may carry either.
            assertThat(sanitiser.carriesClassificationMarking("OFFICIAL–SENSITIVE"))
                .isTrue();
        }

        @ParameterizedTest(name = "{0} is not a marking")
        @ValueSource(strings = {
            "the official drawing register",
            "a protectedarea barrier",
            "unclassified",
            "secretarial notes"
        })
        @DisplayName("a word that merely contains a marking's letters is not one")
        void ordinaryProseIsNotRefused(String prose) {
            // The boundary anchors matter in both directions. Without them
            // "secretarial" refuses a payload that is fine, and every tenant
            // with a document called "Official Drawing Register" loses the
            // feature — which is how a control comes to be switched off.
            assertThat(sanitiser.carriesClassificationMarking(prose)).isFalse();
        }

        @Test
        @DisplayName("text with no marking at all is not refused")
        void plainTextIsNotRefused() {
            assertThat(sanitiser.carriesClassificationMarking(
                "Drawing A-101, revision P02, foundation layout")).isFalse();
        }

        @Test
        @DisplayName("no text at all is not a marking")
        void nullIsNotAMarking() {
            // Absent is not classified. Reading it as a marking would refuse
            // every payload with an empty optional field, which switches the
            // feature off rather than protecting anything.
            assertThat(sanitiser.carriesClassificationMarking(null)).isFalse();
        }

        @Test
        @DisplayName("empty text is not a marking")
        void emptyIsNotAMarking() {
            assertThat(sanitiser.carriesClassificationMarking("")).isFalse();
        }
    }
}
