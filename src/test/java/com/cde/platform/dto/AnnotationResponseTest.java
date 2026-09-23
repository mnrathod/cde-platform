package com.cde.platform.dto;

import com.cde.platform.dto.AnnotationDtos.AnnotationResponse;
import com.cde.platform.model.Annotation;
import com.cde.platform.model.Document;
import com.cde.platform.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a piece of markup looks like once it leaves the API.
 *
 * <p>This mapping had no test at all while it sat as a private method in two
 * controllers, which was found the way these things usually are: by breaking
 * it deliberately and watching the suite stay green. Dropping the author from
 * every response passed every test in the build. That is a visible product
 * defect — who raised a comment is the first thing another reviewer looks for
 * — and it reaches the browser through the annotation list, the XFDF import
 * and the live collaboration feed alike.
 *
 * <p>The two nullable relations are the interesting cases, because they are
 * where the mapping does something other than copy a field, and where the two
 * private copies could have drifted apart.
 */
class AnnotationResponseTest {

    private static Annotation markupBy(User author, Document document) {
        return Annotation.builder()
            .id(9042L)
            .author(author)
            .document(document)
            .type(Annotation.AnnotationType.HIGHLIGHT)
            .status(Annotation.AnnotationStatus.OPEN)
            .shapeData("{\"x\":10}")
            .comment("Level confirmed at 24.150.")
            .pageNumber(3)
            .createdAt(LocalDateTime.of(2026, 4, 1, 9, 30))
            .build();
    }

    @Test
    @DisplayName("the author's name travels with the markup")
    void theAuthorIsNamed() {
        User author = User.builder().username("j.okafor").build();

        assertThat(AnnotationResponse.of(markupBy(author, null)).author())
            .as("who raised a comment is the first thing another reviewer looks for")
            .isEqualTo("j.okafor");
    }

    @Test
    @DisplayName("markup with no author reads as unattributed, not as a failure")
    void aMissingAuthorIsNull() {
        // An import can carry markup whose author is not a user here. That is
        // a normal case, so it renders as an absent name rather than throwing.
        assertThat(AnnotationResponse.of(markupBy(null, null)).author()).isNull();
    }

    @Test
    @DisplayName("the document it belongs to travels with it")
    void theDocumentIsNamed() {
        Document document = new Document();
        document.setId(1180L);

        assertThat(AnnotationResponse.of(markupBy(null, document)).documentId()).isEqualTo(1180L);
    }

    @Test
    @DisplayName("markup detached from its document does not fail the whole list")
    void aMissingDocumentIsNull() {
        assertThat(AnnotationResponse.of(markupBy(null, null)).documentId()).isNull();
    }

    @Test
    @DisplayName("everything else is carried across unchanged")
    void theRestIsCopied() {
        AnnotationResponse response = AnnotationResponse.of(markupBy(null, null));

        assertThat(response.id()).isEqualTo(9042L);
        assertThat(response.type()).isEqualTo(Annotation.AnnotationType.HIGHLIGHT);
        assertThat(response.status()).isEqualTo(Annotation.AnnotationStatus.OPEN);
        assertThat(response.shapeData()).isEqualTo("{\"x\":10}");
        assertThat(response.comment()).isEqualTo("Level confirmed at 24.150.");
        assertThat(response.pageNumber()).isEqualTo(3);
        assertThat(response.createdAt()).isEqualTo(LocalDateTime.of(2026, 4, 1, 9, 30));
    }
}
