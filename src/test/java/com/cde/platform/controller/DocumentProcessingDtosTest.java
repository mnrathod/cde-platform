package com.cde.platform.controller;

import com.cde.platform.controller.DocumentProcessingDtos.AddFieldsRequest;
import com.cde.platform.controller.DocumentProcessingDtos.FieldRequest;
import com.cde.platform.controller.DocumentProcessingDtos.FlattenRequest;
import com.cde.platform.controller.DocumentProcessingDtos.OcrRequest;
import com.cde.platform.controller.DocumentProcessingDtos.TextSearchRequest;
import com.cde.platform.dto.RedactionPreset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a processing request means when the caller left things out.
 *
 * <p>These records are where the API's published defaults actually live. Every
 * one of them is documented in the specification — `defaultValue = "300"`,
 * `"anything else is treated as screen"` — and a default that the schema
 * promises and the code does not apply is a bug a customer finds rather than a
 * build does. Unit tests with no Spring context, because the defaulting is
 * arithmetic and string comparison; putting a database behind it would only
 * make it slower to find out.
 *
 * <p>The awkward cases are the ones worth having: a boolean that is null
 * rather than false, a clamp at both ends and on the boundary itself, and a
 * field kind that does not exist — which has to say what the permitted ones
 * are, because the caller cannot otherwise tell a typo from an unsupported
 * feature.
 */
class DocumentProcessingDtosTest {

    @Nested
    @DisplayName("how a flatten request is rendered")
    class Flattening {

        private FlattenRequest quality(String quality) {
            return new FlattenRequest(List.of(), quality);
        }

        @Test
        @DisplayName("print quality is honoured")
        void honoursPrint() {
            assertThat(quality("print").qualityOrDefault()).isEqualTo("print");
        }

        @Test
        @DisplayName("print is recognised whatever case it was sent in")
        void printIsCaseInsensitive() {
            assertThat(quality("PRINT").qualityOrDefault()).isEqualTo("print");
            assertThat(quality("Print").qualityOrDefault()).isEqualTo("print");
        }

        @Test
        @DisplayName("nothing at all means screen")
        void defaultsToScreen() {
            assertThat(quality(null).qualityOrDefault()).isEqualTo("screen");
        }

        @Test
        @DisplayName("anything unrecognised means screen rather than failing")
        void unknownQualityIsScreen() {
            // The schema says so in as many words. Refusing instead would make
            // a typo cost a request that could have been served.
            assertThat(quality("archival").qualityOrDefault()).isEqualTo("screen");
            assertThat(quality("").qualityOrDefault()).isEqualTo("screen");
        }
    }

    @Nested
    @DisplayName("how a recognition request is set up")
    class Recognition {

        private OcrRequest ocr(String lang, Integer dpi, Boolean skip) {
            return new OcrRequest(lang, dpi, skip);
        }

        @Test
        @DisplayName("an empty body is a valid request")
        void emptyBodyIsValid() {
            // The schema promises it, and the endpoint accepts a body-less
            // POST on the strength of that.
            OcrRequest empty = ocr(null, null, null);

            assertThat(empty.languageOrDefault()).isEqualTo("eng");
            assertThat(empty.dpiOrDefault()).isEqualTo(300);
            assertThat(empty.skipTextPagesOrDefault()).isTrue();
        }

        @Test
        @DisplayName("a named language is used")
        void usesTheNamedLanguage() {
            assertThat(ocr("deu", null, null).languageOrDefault()).isEqualTo("deu");
        }

        @Test
        @DisplayName("a blank language falls back rather than asking for a pack called ' '")
        void blankLanguageFallsBack() {
            assertThat(ocr("   ", null, null).languageOrDefault()).isEqualTo("eng");
            assertThat(ocr("", null, null).languageOrDefault()).isEqualTo("eng");
        }

        @Test
        @DisplayName("a resolution in range is used as asked")
        void usesAResolutionInRange() {
            assertThat(ocr(null, 400, null).dpiOrDefault()).isEqualTo(400);
        }

        @Test
        @DisplayName("too low is raised to where accuracy survives")
        void clampsUpward() {
            assertThat(ocr(null, 72, null).dpiOrDefault()).isEqualTo(150);
            assertThat(ocr(null, 0, null).dpiOrDefault()).isEqualTo(150);
            assertThat(ocr(null, -300, null).dpiOrDefault()).isEqualTo(150);
        }

        @Test
        @DisplayName("too high is lowered rather than refused")
        void clampsDownward() {
            // Clamped, not rejected: a caller asking for 1200 wants the best
            // available and should not have to guess the limit.
            assertThat(ocr(null, 1200, null).dpiOrDefault()).isEqualTo(600);
            assertThat(ocr(null, Integer.MAX_VALUE, null).dpiOrDefault()).isEqualTo(600);
        }

        @ParameterizedTest
        @ValueSource(ints = {150, 600})
        @DisplayName("the limits themselves are inside the range, not outside it")
        void boundariesAreInclusive(int dpi) {
            // An off-by-one here would silently move somebody's 600 dpi scan
            // to 599 or refuse it.
            assertThat(ocr(null, dpi, null).dpiOrDefault()).isEqualTo(dpi);
        }

        @Test
        @DisplayName("skipping pages that already have text is the default")
        void skipsTextPagesByDefault() {
            // Recognising over real text replaces it with a guess at the same
            // words, so the safe default is to leave it alone.
            assertThat(ocr(null, null, null).skipTextPagesOrDefault()).isTrue();
        }

        @Test
        @DisplayName("asking not to skip them is honoured")
        void honoursNotSkipping() {
            // The case a null-means-true default gets wrong if it is written
            // as a truthiness check rather than a null check.
            assertThat(ocr(null, null, false).skipTextPagesOrDefault()).isFalse();
        }

        @Test
        @DisplayName("asking to skip them is honoured too")
        void honoursSkipping() {
            assertThat(ocr(null, null, true).skipTextPagesOrDefault()).isTrue();
        }
    }

    @Nested
    @DisplayName("how a text search is built")
    class Searching {

        @Test
        @DisplayName("literal terms are carried through")
        void carriesTerms() {
            var search = new TextSearchRequest(
                List.of("Confidential"), null, null, null, null).toSearch();

            assertThat(search.terms()).containsExactly("Confidential");
        }

        @Test
        @DisplayName("presets travel as their names, not as enum objects")
        void presetsTravelAsNames() {
            // The conversion service maintains the patterns; it is handed the
            // category name and nothing else.
            var search = new TextSearchRequest(
                null, List.of(RedactionPreset.values()[0]), null, null, null).toSearch();

            assertThat(search.presets()).containsExactly(RedactionPreset.values()[0].name());
        }

        @Test
        @DisplayName("no presets is an empty list rather than null")
        void absentPresetsAreEmpty() {
            // A null crossing this boundary would reach the converter as the
            // JSON literal null, which it reads differently from "none".
            var search = new TextSearchRequest(
                List.of("x"), null, null, null, null).toSearch();

            assertThat(search.presets()).isEmpty();
        }

        @Test
        @DisplayName("case sensitivity is off unless asked for")
        void matchCaseDefaultsOff() {
            assertThat(new TextSearchRequest(List.of("x"), null, null, null, null)
                .toSearch().matchCase()).isFalse();
        }

        @Test
        @DisplayName("case sensitivity is honoured when asked for")
        void honoursMatchCase() {
            assertThat(new TextSearchRequest(List.of("x"), null, null, true, null)
                .toSearch().matchCase()).isTrue();
        }

        @Test
        @DisplayName("whole-word matching is off unless asked for")
        void wholeWordDefaultsOff() {
            assertThat(new TextSearchRequest(List.of("x"), null, null, null, null)
                .toSearch().wholeWord()).isFalse();
            assertThat(new TextSearchRequest(List.of("x"), null, null, null, false)
                .toSearch().wholeWord()).isFalse();
        }

        @Test
        @DisplayName("whole-word matching is honoured when asked for")
        void honoursWholeWord() {
            assertThat(new TextSearchRequest(List.of("x"), null, null, null, true)
                .toSearch().wholeWord()).isTrue();
        }

        @Test
        @DisplayName("regexes are carried through")
        void carriesRegexes() {
            var search = new TextSearchRequest(
                null, null, List.of("[A-Z]{3}-[0-9]{4}"), null, null).toSearch();

            assertThat(search.regexes()).containsExactly("[A-Z]{3}-[0-9]{4}");
        }
    }

    @Nested
    @DisplayName("how a form field is placed")
    class PlacingFields {

        private FieldRequest field(String kind, Integer page, Float x, Float y,
                                   Boolean required, List<String> options) {
            return new FieldRequest("contractor_name", kind, page, x, y, 220f, 18f,
                                    required, options);
        }

        @Test
        @DisplayName("a named kind is used")
        void usesTheNamedKind() {
            assertThat(field("CHECKBOX", null, null, null, null, null)
                .toPlacement().kind().name()).isEqualTo("CHECKBOX");
        }

        @Test
        @DisplayName("a kind is read whatever case and spacing it was sent in")
        void kindIsForgivingOfFormatting() {
            assertThat(field(" dropdown ", null, null, null, null, null)
                .toPlacement().kind().name()).isEqualTo("DROPDOWN");
        }

        @Test
        @DisplayName("no kind means a text field")
        void defaultsToText() {
            assertThat(field(null, null, null, null, null, null)
                .toPlacement().kind().name()).isEqualTo("TEXT");
        }

        @Test
        @DisplayName("a kind that does not exist says which ones do")
        void unknownKindNamesThePermittedOnes() {
            // Without the list a caller cannot tell a typo from a feature this
            // deployment does not support.
            assertThatThrownBy(() -> field("SIGNATURE", null, null, null, null, null)
                    .toPlacement())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SIGNATURE")
                .hasMessageContaining("TEXT")
                .hasMessageContaining("DROPDOWN");
        }

        @Test
        @DisplayName("no page means the first one")
        void defaultsToPageOne() {
            assertThat(field(null, null, null, null, null, null).toPlacement().page())
                .isEqualTo(1);
        }

        @Test
        @DisplayName("a named page is used")
        void usesTheNamedPage() {
            assertThat(field(null, 4, null, null, null, null).toPlacement().page())
                .isEqualTo(4);
        }

        @Test
        @DisplayName("missing coordinates place the field at the origin rather than failing")
        void missingCoordinatesBecomeZero() {
            var placement = field(null, null, null, null, null, null).toPlacement();

            assertThat(placement.x()).isZero();
            assertThat(placement.y()).isZero();
        }

        @Test
        @DisplayName("given coordinates are used")
        void usesGivenCoordinates() {
            var placement = field(null, null, 72f, 540f, null, null).toPlacement();

            assertThat(placement.x()).isEqualTo(72f);
            assertThat(placement.y()).isEqualTo(540f);
        }

        @Test
        @DisplayName("a field is optional unless it says otherwise")
        void optionalByDefault() {
            assertThat(field(null, null, null, null, null, null).toPlacement().required())
                .isFalse();
            assertThat(field(null, null, null, null, false, null).toPlacement().required())
                .isFalse();
        }

        @Test
        @DisplayName("a required field is required")
        void honoursRequired() {
            assertThat(field(null, null, null, null, true, null).toPlacement().required())
                .isTrue();
        }

        @Test
        @DisplayName("no options is an empty list rather than null")
        void absentOptionsAreEmpty() {
            assertThat(field(null, null, null, null, null, null).toPlacement().options())
                .isEmpty();
        }

        @Test
        @DisplayName("options are carried through for a dropdown")
        void carriesOptions() {
            assertThat(field("DROPDOWN", null, null, null, null,
                             List.of("Structural", "Services"))
                .toPlacement().options())
                .containsExactly("Structural", "Services");
        }

        @Test
        @DisplayName("a batch of fields becomes a batch of placements, in order")
        void convertsAWholeBatch() {
            var batch = new AddFieldsRequest(List.of(
                field("TEXT", 1, null, null, null, null),
                field("CHECKBOX", 2, null, null, null, null)));

            assertThat(batch.toPlacements()).hasSize(2);
            assertThat(batch.toPlacements().get(1).page()).isEqualTo(2);
        }

        @Test
        @DisplayName("a batch with no fields converts to nothing rather than throwing")
        void handlesAnAbsentBatch() {
            // Validation refuses this at the boundary, so reaching here means
            // something called it directly — and an exception at that point
            // would be a fault in the wrong place.
            assertThat(new AddFieldsRequest(null).toPlacements()).isEmpty();
        }
    }
}
