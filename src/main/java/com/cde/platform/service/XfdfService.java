package com.cde.platform.service;

import com.cde.platform.model.Annotation;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Converts CDE annotations to and from XFDF (XML Forms Data Format).
 *
 * <p>XFDF is the Adobe/PDF interchange format that Bluebeam, Acrobat and
 * Procore all read, which is what makes markup portable off this platform.
 * Specification: PDF 32000-1:2008.
 *
 * <p>The work lives in {@link XfdfWriter} and {@link XfdfReader}. This class
 * is the entry point callers and tests already use, so splitting the
 * implementation changed nothing outside this package.
 */
@Service
public class XfdfService {

    /**
     * Tolerance for treating a bounding box as square, and so a shape as a
     * circle rather than an ellipse.
     *
     * <p>Shared by the writer and the reader deliberately. The writer uses it
     * to decide which element to emit and the reader uses it to decide how to
     * read one back, so two copies that drifted apart would make the round
     * trip asymmetric — a circle would come back as an ellipse.
     */
    static final double SHAPE_EPSILON = 0.01;

    private final XfdfWriter writer = new XfdfWriter();
    private final XfdfReader reader = new XfdfReader();

    /** Renders annotations as an XFDF document referencing {@code filename}. */
    public String toXfdf(List<Annotation> annotations, String filename) {
        return writer.toXfdf(annotations, filename);
    }

    /**
     * Reads an XFDF document back into annotations.
     *
     * <p>Malformed elements are skipped rather than failing the import: XFDF
     * arrives from other vendors' tools, so a file that is well-formed XML and
     * nonsense inside is the normal case, and salvaging eleven of twelve
     * markups beats rejecting the file.
     */
    public List<ImportedAnnotation> fromXfdf(byte[] xfdfBytes) throws Exception {
        return reader.fromXfdf(xfdfBytes);
    }

    public record ImportedAnnotation(
        Annotation.AnnotationType type,
        String shapeData,
        String comment,
        int    pageNumber,
        String authorName
    ) {}
}
