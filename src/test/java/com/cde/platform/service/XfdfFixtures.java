package com.cde.platform.service;

import com.cde.platform.model.Annotation;
import com.cde.platform.model.User;

import java.time.LocalDateTime;

/**
 * The markup the XFDF tests start from.
 *
 * <p>Shared because all three of the XFDF suites — export, import and round
 * trip — need the same starting annotation, and three private copies of a
 * builder is how three suites come to be testing three slightly different
 * things while appearing to agree.
 *
 * <p>The timestamp is fixed. XFDF carries a creation date, and a clock in a
 * fixture makes an assertion about the exported envelope pass or fail by the
 * hour it is run.
 */
final class XfdfFixtures {

    static final LocalDateTime DRAWN_AT = LocalDateTime.of(2026, 8, 7, 12, 0, 0);
    static final String AUTHOR = "engineer1";

    private XfdfFixtures() {
    }

    static Annotation annotation(Annotation.AnnotationType type, String shapeData) {
        return annotation(type, shapeData, 1, "");
    }

    static Annotation annotation(Annotation.AnnotationType type, String shapeData,
                                 int pageNumber, String comment) {
        Annotation annotation = new Annotation();
        annotation.setType(type);
        annotation.setShapeData(shapeData);
        annotation.setComment(comment);
        annotation.setPageNumber(pageNumber);
        annotation.setAuthor(User.builder().username(AUTHOR).build());
        annotation.setCreatedAt(DRAWN_AT);
        return annotation;
    }
}
