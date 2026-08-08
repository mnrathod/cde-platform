package com.cde.platform.service;

import com.cde.platform.service.PageArrangement.PageRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The arrangement is what the page organiser sends and what the version
 * history describes, so its summary is user-facing text — worth testing
 * directly rather than through a converter call.
 */
class PageArrangementTest {

    private static PageArrangement of(int... pages) {
        return new PageArrangement(java.util.Arrays.stream(pages).mapToObj(PageRef::of).toList());
    }

    @Nested
    @DisplayName("page references")
    class PageRefs {

        @Test
        @DisplayName("normalises rotation into 0-359")
        void normalisesRotation() {
            assertThat(new PageRef(null, 1, 450).rotate()).isEqualTo(90);
            assertThat(new PageRef(null, 1, 360).rotate()).isEqualTo(0);
            assertThat(new PageRef(null, 1, -90).rotate()).isEqualTo(270);
        }

        @Test
        @DisplayName("a page without a source belongs to the document being arranged")
        void ownPages() {
            assertThat(PageRef.of(1).isOwn()).isTrue();
            assertThat(new PageRef("insert", 1, 0).isOwn()).isFalse();
        }
    }

    @Nested
    @DisplayName("identity")
    class Identity {

        @Test
        @DisplayName("lists every page in order")
        void listsEveryPage() {
            assertThat(PageArrangement.identity(3).pages())
                .extracting(PageRef::page).containsExactly(1, 2, 3);
        }

        @Test
        @DisplayName("reports no change")
        void reportsNoChange() {
            assertThat(PageArrangement.identity(3).describeChangeFrom(3))
                .isEqualTo("No page changes");
        }
    }

    @Nested
    @DisplayName("change descriptions")
    class Descriptions {

        @Test
        @DisplayName("names deleted pages")
        void deletion() {
            assertThat(of(1, 3).describeChangeFrom(3)).isEqualTo("Deleted 1 page");
            assertThat(of(2).describeChangeFrom(4)).isEqualTo("Deleted 3 pages");
        }

        @Test
        @DisplayName("names duplicated pages")
        void duplication() {
            assertThat(of(1, 1, 2).describeChangeFrom(2)).isEqualTo("Duplicated 1 page");
            assertThat(of(1, 1, 1, 2).describeChangeFrom(2)).isEqualTo("Duplicated 2 pages");
        }

        @Test
        @DisplayName("names rotated pages")
        void rotation() {
            var arrangement = new PageArrangement(List.of(
                new PageRef(null, 1, 90), PageRef.of(2)));
            assertThat(arrangement.describeChangeFrom(2)).isEqualTo("Rotated 1 page");
        }

        @Test
        @DisplayName("reports reordering when retained pages change places")
        void reordering() {
            assertThat(of(2, 1).describeChangeFrom(2)).isEqualTo("Reordered pages");
        }

        @Test
        @DisplayName("dropping a page from the middle is not itself a reorder")
        void deletionIsNotReordering() {
            // 1,3 is still ascending — calling that "reordered" would be noise.
            assertThat(of(1, 3).describeChangeFrom(3)).isEqualTo("Deleted 1 page");
        }

        @Test
        @DisplayName("names inserted pages")
        void insertion() {
            var arrangement = new PageArrangement(List.of(
                PageRef.of(1), new PageRef("insert", 1, 0), PageRef.of(2)));
            assertThat(arrangement.describeChangeFrom(2)).isEqualTo("Inserted 1 page");
        }

        @Test
        @DisplayName("combines several changes into one readable line")
        void combinesChanges() {
            var arrangement = new PageArrangement(List.of(
                new PageRef(null, 3, 90), PageRef.of(1), PageRef.of(1)));
            // Page 2 dropped, page 1 repeated, page 3 turned and moved first.
            assertThat(arrangement.describeChangeFrom(3))
                .isEqualTo("Deleted 1 page, duplicated 1 page, rotated 1 page, reordered pages");
        }
    }

    @Nested
    @DisplayName("converter plan")
    class Plan {

        @Test
        @DisplayName("carries source, page and rotation for each entry")
        void carriesEveryField() {
            var arrangement = new PageArrangement(List.of(
                new PageRef(null, 2, 90), new PageRef("insert", 1, 0)));

            List<Map<String, Object>> plan = arrangement.toPlan();

            assertThat(plan).hasSize(2);
            assertThat(plan.get(0)).containsEntry("source", null)
                                   .containsEntry("page", 2)
                                   .containsEntry("rotate", 90);
            assertThat(plan.get(1)).containsEntry("source", "insert")
                                   .containsEntry("page", 1)
                                   .containsEntry("rotate", 0);
        }

        @Test
        @DisplayName("preserves order — the plan is the page order")
        void preservesOrder() {
            assertThat(of(3, 1, 2).toPlan())
                .extracting(entry -> entry.get("page"))
                .containsExactly(3, 1, 2);
        }
    }

    @Test
    @DisplayName("an empty arrangement is recognised, since it would erase the document")
    void emptyIsRecognised() {
        assertThat(new PageArrangement(List.of()).isEmpty()).isTrue();
        assertThat(of(1).isEmpty()).isFalse();
    }
}
