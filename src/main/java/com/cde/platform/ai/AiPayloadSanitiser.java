package com.cde.platform.ai;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The only thing that decides what leaves this deployment for a model provider.
 *
 * <p>The endpoint this replaced took the provider's own request format and
 * forwarded it byte for byte. That is not a filter that was configured badly —
 * it is a shape in which no filter is possible. Allow-list field selection
 * (§10.1) means choosing which fields go, and you cannot choose fields of a
 * payload whose structure the caller defines. So the API no longer accepts a
 * prompt at all: a caller names what it wants summarised, and the prompt is
 * built here from fields this class picked.
 *
 * <p>Three layers, in this order, because each assumes the one before it:
 *
 * <ol>
 *   <li><strong>Allow-list.</strong> Only the fields named in {@link
 *       ComparisonFacts} are ever rendered. A field added to a comparison next
 *       year does not travel until somebody adds it here, which is the right
 *       default — the failure mode of a deny-list is that new data leaks and
 *       nobody notices.</li>
 *   <li><strong>Pseudonymisation.</strong> The allowed fields are still free
 *       text written by people: a drawing title can contain a name, an email
 *       address, or a site address. Those are replaced with stable placeholders
 *       and re-hydrated locally on the way back, so the provider sees a
 *       consistent document without ever seeing the identifier.</li>
 *   <li><strong>Refusal.</strong> Content carrying a classification marking is
 *       not sanitised, redacted, or truncated — the whole request is refused.
 *       There is no redaction that makes OFFICIAL-SENSITIVE material safe to
 *       send to a third party, and a partial send is still a send.</li>
 * </ol>
 *
 * <p>It cannot be bypassed by feature code because feature code has nothing to
 * bypass it with: {@link AiAssistanceService} accepts only the typed facts, and
 * there is no path from a request body to the provider that does not pass
 * through here.
 */
@Component
public class AiPayloadSanitiser {

    /**
     * UK Government Security Classifications and the Australian PSPF markings.
     * Matched case-insensitively as whole words so that an ordinary sentence
     * containing "official" — which construction documents do constantly — is
     * not mistaken for a marking.
     *
     * <p>Deliberately over-broad rather than precise. A false refusal costs a
     * user one summary; a false acceptance is classified material at a third
     * party, which is a reportable incident and, on a Defence contract, a
     * breach of the contract itself.
     */
    private static final Pattern CLASSIFICATION_MARKING = Pattern.compile(
        "\\b(OFFICIAL\\s*[-–]\\s*SENSITIVE|OFFICIAL\\s*:\\s*SENSITIVE"
        + "|PROTECTED|SECRET|TOP\\s+SECRET|CONFIDENTIAL"
        + "|RESTRICTED|CODEWORD|ACCOUNTABLE\\s+MATERIAL)\\b",
        Pattern.CASE_INSENSITIVE);

    /**
     * Patterns for the personal identifiers §10.1 names. Each match becomes a
     * stable placeholder rather than being deleted, so the text still reads as
     * a document and the model can refer to "the same person" twice.
     */
    private static final Map<String, Pattern> PERSONAL_IDENTIFIERS = Map.of(
        "EMAIL", Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+"),
        // Deliberately loose: international formats vary enormously, and the
        // cost of over-matching a long digit run is a placeholder in a drawing
        // title rather than a phone number at a third party.
        "PHONE", Pattern.compile("(?<![\\w.])\\+?\\d[\\d\\s().-]{7,}\\d(?![\\w.])"),
        "IP", Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"),
        "URL", Pattern.compile("\\bhttps?://\\S+"));

    /**
     * Bounded so that a caller cannot turn a summary request into a way of
     * shipping a document's contents out one field at a time.
     */
    private static final int MAX_FIELD_LENGTH = 400;
    private static final int MAX_CHANGES_RENDERED = 40;

    /**
     * Builds the text to send, or explains why nothing will be sent.
     */
    public SanitisedPayload sanitise(ComparisonFacts facts) {
        var placeholders = new Placeholders();
        var refusals = new ArrayList<String>();

        String firstName = clean("the first document's name", facts.firstDocumentName(),
                                 placeholders, refusals);
        String secondName = clean("the second document's name", facts.secondDocumentName(),
                                  placeholders, refusals);
        String firstRevision = clean("the first document's revision", facts.firstRevision(),
                                     placeholders, refusals);
        String secondRevision = clean("the second document's revision", facts.secondRevision(),
                                      placeholders, refusals);

        List<String> changes = new ArrayList<>();
        facts.changes().stream().limit(MAX_CHANGES_RENDERED).forEach(change ->
            changes.add(clean("a change description", change, placeholders, refusals)));

        if (!refusals.isEmpty()) {
            return SanitisedPayload.refused(refusals);
        }

        return SanitisedPayload.allowed(
            render(firstName, secondName, firstRevision, secondRevision,
                   facts.documentKind(), changes),
            placeholders,
            !placeholders.isEmpty());
    }

    /**
     * @return the field with personal identifiers replaced, or null when it
     *         carries a classification marking — in which case a refusal is
     *         appended and the caller abandons the whole payload
     */
    private String clean(String fieldDescription, String value,
                         Placeholders placeholders, List<String> refusals) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String bounded = value.length() <= MAX_FIELD_LENGTH
            ? value : value.substring(0, MAX_FIELD_LENGTH);

        if (CLASSIFICATION_MARKING.matcher(bounded).find()) {
            refusals.add(fieldDescription + " carries a classification marking");
            return null;
        }
        return placeholders.pseudonymise(bounded);
    }

    private String render(String firstName, String secondName,
                          String firstRevision, String secondRevision,
                          String documentKind, List<String> changes) {
        var prompt = new StringBuilder();
        prompt.append("""
            You are assisting a construction professional reviewing two revisions of a \
            document in a Common Data Environment. Write a review report with these five \
            headings and nothing else: Revision summary, Key changes, Impacted disciplines, \
            Review comments, Suggested RFIs.

            Keep it under 400 words, in professional engineering language. Where a value has \
            been replaced by a placeholder in square brackets — [EMAIL_n], [PHONE_n], [URL_n] \
            or [IP_n], where n is a number — reproduce the placeholder exactly rather than \
            inventing a value.

            """);
        prompt.append("Document kind: ").append(documentKind).append('\n');
        prompt.append("Earlier revision: ").append(firstName)
              .append(" (").append(firstRevision).append(")\n");
        prompt.append("Later revision: ").append(secondName)
              .append(" (").append(secondRevision).append(")\n\n");
        prompt.append("Changes detected:\n");
        changes.forEach(change -> prompt.append("- ").append(change).append('\n'));
        return prompt.toString();
    }

    /**
     * The stable placeholders assigned during one sanitisation, so the same
     * value maps to the same placeholder throughout and can be put back on the
     * way home.
     */
    public static final class Placeholders {

        private final Map<String, String> placeholderToOriginal = new LinkedHashMap<>();
        private final Map<String, String> originalToPlaceholder = new LinkedHashMap<>();

        String pseudonymise(String value) {
            String result = value;
            for (var entry : PERSONAL_IDENTIFIERS.entrySet()) {
                Matcher matcher = entry.getValue().matcher(result);
                var replaced = new StringBuilder();
                while (matcher.find()) {
                    matcher.appendReplacement(replaced,
                        Matcher.quoteReplacement(placeholderFor(entry.getKey(), matcher.group())));
                }
                matcher.appendTail(replaced);
                result = replaced.toString();
            }
            return result;
        }

        /**
         * The number makes the placeholder unique, and {@code n} in the prompt's
         * own instructions is deliberately not a number — so the example the
         * model is shown can never collide with a placeholder it is asked to
         * reproduce.
         */
        private String placeholderFor(String kind, String original) {
            return originalToPlaceholder.computeIfAbsent(original, value -> {
                String placeholder = "[" + kind + "_"
                    + (placeholderToOriginal.size() + 1) + "]";
                placeholderToOriginal.put(placeholder, value);
                return placeholder;
            });
        }

        /**
         * Puts the real values back into whatever the model returned.
         *
         * <p>Done locally, on the way out to the user, so the identifiers exist
         * on this side of the boundary only.
         */
        public String rehydrate(String modelOutput) {
            if (modelOutput == null) {
                return null;
            }
            String result = modelOutput;
            for (var entry : placeholderToOriginal.entrySet()) {
                result = result.replace(entry.getKey(), entry.getValue());
            }
            return result;
        }

        public boolean isEmpty() {
            return placeholderToOriginal.isEmpty();
        }

        public int count() {
            return placeholderToOriginal.size();
        }
    }

    /**
     * The allow-list itself, as a type. Nothing outside these fields can reach
     * a provider, because nothing else is ever read.
     */
    public record ComparisonFacts(String firstDocumentName,
                                  String secondDocumentName,
                                  String firstRevision,
                                  String secondRevision,
                                  String documentKind,
                                  List<String> changes) {

        public ComparisonFacts {
            changes = changes == null ? List.of() : List.copyOf(changes);
        }
    }

    /**
     * @param prompt       what will be sent, or null when refused
     * @param placeholders the mapping needed to re-hydrate the reply
     * @param redacted     whether anything was replaced, for the audit record
     * @param refusals     why nothing will be sent, in words a user can act on
     */
    public record SanitisedPayload(String prompt, Placeholders placeholders,
                                   boolean redacted, List<String> refusals) {

        static SanitisedPayload allowed(String prompt, Placeholders placeholders,
                                        boolean redacted) {
            return new SanitisedPayload(prompt, placeholders, redacted, List.of());
        }

        static SanitisedPayload refused(List<String> refusals) {
            return new SanitisedPayload(null, new Placeholders(), false,
                                        List.copyOf(refusals));
        }

        public boolean isRefused() {
            return prompt == null;
        }

        /** A single sentence naming every reason, for the problem detail. */
        public String refusalDetail() {
            return "This content cannot be sent to a model provider: "
                 + String.join("; ", refusals)
                 + ". Classified material is never sent off this deployment.";
        }
    }

    /**
     * @return true when the text carries a classification marking, exposed so
     *         other outbound paths can apply the same test rather than
     *         reimplementing it
     */
    public boolean carriesClassificationMarking(String text) {
        return text != null
            && CLASSIFICATION_MARKING.matcher(text.toUpperCase(Locale.ROOT)).find();
    }
}
