package com.cde.platform.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceCharacteristicsDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Adds and removes AcroForm fields, turning a plain PDF into a fillable one.
 *
 * <p>Filling only ever worked on documents that already had fields, which
 * leaves out the common case entirely: a drawing or a report issued as a flat
 * PDF that someone now needs signed off against. Placing the fields was the
 * missing half.
 *
 * <p>PDFBox rather than the Python converter because building a form means
 * constructing widget annotations, appearance streams and a default
 * resources dictionary — objects the PDF library models directly and that
 * would otherwise have to be assembled by hand.
 */
@Service
public class FormFieldBuilder {

    private static final Logger log = LoggerFactory.getLogger(FormFieldBuilder.class);

    /** Default appearance: 10pt Helvetica, black — a readable, neutral field. */
    private static final String DEFAULT_APPEARANCE = "/Helv 10 Tf 0 g";
    private static final String FONT_RESOURCE_NAME = "Helv";

    /** Appearance-state names for a checkbox. */
    private static final String CHECKBOX_ON_STATE   = "Yes";
    private static final String CHECKBOX_OFF_STATE  = "Off";


    /** The control a placed field should be. */
    public enum FieldKind { TEXT, TEXTAREA, CHECKBOX, DROPDOWN }

    /**
     * A field to place.
     *
     * @param page    1-based page number
     * @param x       distance from the left edge, PDF points
     * @param y       distance from the bottom edge, PDF points
     * @param options choices for a dropdown; ignored for other kinds
     */
    public record FieldPlacement(
        String    name,
        FieldKind kind,
        int       page,
        float     x,
        float     y,
        float     width,
        float     height,
        boolean   required,
        List<String> options
    ) {}

    /**
     * Adds fields to a document.
     *
     * @return the names actually added, in order
     * @throws IOException if the document cannot be read or written
     * @throws IllegalArgumentException if a placement is unusable
     */
    public List<String> addFields(Path source, Path target, List<FieldPlacement> placements)
        throws IOException {

        List<String> added = new ArrayList<>();

        try (PDDocument document = Loader.loadPDF(source.toFile())) {
            PDAcroForm form = acroFormOf(document);

            for (FieldPlacement placement : placements) {
                validate(placement, document.getNumberOfPages(), form);
                added.add(place(document, form, placement).getPartialName());
            }

            document.save(target.toFile());
        }

        log.info("Added {} form field(s) to {}", added.size(), target.getFileName());
        return added;
    }

    /**
     * Removes named fields and their widgets.
     *
     * @return the names actually removed; a name that was not present is
     *         skipped rather than failing the whole request
     */
    public List<String> removeFields(Path source, Path target, List<String> names)
        throws IOException {

        List<String> removed = new ArrayList<>();

        try (PDDocument document = Loader.loadPDF(source.toFile())) {
            PDAcroForm form = document.getDocumentCatalog().getAcroForm();
            if (form == null) return removed;

            List<PDField> keep = new ArrayList<>();

            for (PDField field : form.getFields()) {
                if (!names.contains(field.getPartialName())) {
                    keep.add(field);
                    continue;
                }

                // The widget lives on the page as an annotation as well as in
                // the form's field list; leaving it behind would draw a box
                // that no longer belongs to any field.
                for (PDAnnotationWidget widget : field.getWidgets()) {
                    PDPage page = widget.getPage();
                    if (page != null) page.getAnnotations().remove(widget);
                }
                removed.add(field.getPartialName());
            }

            // Rebuilt by name and assigned back, rather than removing the
            // object getField() returns: that is a fresh wrapper around the
            // same dictionary, so removing it by equality matches nothing.
            form.setFields(keep);
            document.save(target.toFile());
        }
        return removed;
    }

    // ── Internals ────────────────────────────────────────────────

    /** The document's form, created with usable defaults if it has none. */
    private PDAcroForm acroFormOf(PDDocument document) throws IOException {
        PDAcroForm existing = document.getDocumentCatalog().getAcroForm();
        if (existing != null) {
            ensureDefaultResources(existing);
            return existing;
        }

        PDAcroForm form = new PDAcroForm(document);
        ensureDefaultResources(form);
        document.getDocumentCatalog().setAcroForm(form);
        return form;
    }

    /**
     * Gives the form a font to render field text with.
     *
     * <p>Without a default appearance and a matching resource, viewers have
     * nothing to draw typed text with and the field renders empty however
     * much the user types into it.
     */
    private void ensureDefaultResources(PDAcroForm form) throws IOException {
        PDResources resources = form.getDefaultResources();
        if (resources == null) {
            resources = new PDResources();
            form.setDefaultResources(resources);
        }
        if (resources.getFont(COSName.getPDFName(FONT_RESOURCE_NAME)) == null) {
            resources.put(COSName.getPDFName(FONT_RESOURCE_NAME),
                new PDType1Font(Standard14Fonts.FontName.HELVETICA));
        }
        if (form.getDefaultAppearance() == null || form.getDefaultAppearance().isEmpty()) {
            form.setDefaultAppearance(DEFAULT_APPEARANCE);
        }
    }

    private void validate(FieldPlacement placement, int pageCount, PDAcroForm form) {
        if (placement.name() == null || placement.name().isBlank()) {
            throw new IllegalArgumentException("Every field needs a name.");
        }
        if (placement.page() < 1 || placement.page() > pageCount) {
            throw new IllegalArgumentException(
                "Field '%s' is on page %d, but the document has %d."
                    .formatted(placement.name(), placement.page(), pageCount));
        }
        if (placement.width() <= 0 || placement.height() <= 0) {
            throw new IllegalArgumentException(
                "Field '%s' has no area.".formatted(placement.name()));
        }
        if (form.getField(placement.name()) != null) {
            // Two fields sharing a name share a value in the PDF spec, which
            // is almost never what someone drawing a second box meant.
            throw new IllegalArgumentException(
                "This document already has a field called '%s'.".formatted(placement.name()));
        }
        if (placement.kind() == FieldKind.DROPDOWN
            && (placement.options() == null || placement.options().isEmpty())) {
            throw new IllegalArgumentException(
                "Dropdown '%s' needs at least one option.".formatted(placement.name()));
        }
    }

    private PDField place(PDDocument document, PDAcroForm form, FieldPlacement placement)
        throws IOException {

        PDPage page = document.getPage(placement.page() - 1);
        PDField field = switch (placement.kind()) {
            case TEXT, TEXTAREA -> textField(form, placement);
            case CHECKBOX       -> new PDCheckBox(form);
            case DROPDOWN       -> dropdown(form, placement);
        };
        field.setPartialName(placement.name());
        field.setRequired(placement.required());

        PDAnnotationWidget widget = field.getWidgets().get(0);
        widget.setRectangle(new PDRectangle(
            placement.x(), placement.y(), placement.width(), placement.height()));
        widget.setPage(page);
        widget.setPrinted(true);
        widget.setAppearanceCharacteristics(borderAndBackground());

        page.getAnnotations().add(widget);
        form.getFields().add(field);

        // Setting an initial value makes PDFBox construct the appearance
        // stream. A field without one is legal but unusable in practice: it
        // renders as nothing, and tools that fill forms by editing
        // appearances — pypdf among them, which is what fills forms here —
        // fail outright on the missing /AP.
        giveInitialAppearance(document, field, widget);
        return field;
    }

    /**
     * Forces an appearance stream onto a newly created field.
     *
     * <p>A field without one is legal but unusable: it renders as nothing,
     * and tools that fill forms by editing appearances — pypdf among them,
     * which is what fills forms here — fail outright on the missing /AP.
     */
    private void giveInitialAppearance(PDDocument document, PDField field,
                                       PDAnnotationWidget widget) throws IOException {
        if (field instanceof PDTextField text) {
            // Setting a value makes PDFBox construct the stream.
            text.setValue("");
        } else if (field instanceof PDComboBox combo) {
            combo.setValue("");
        } else if (field instanceof PDCheckBox checkBox) {
            // A checkbox has no single appearance: it needs a stream per
            // state, keyed by the name used to turn it on. PDFBox expects the
            // producer to supply those, so they are built here.
            widget.setAppearance(checkBoxStates(document, widget));
            checkBox.unCheck();
        }
    }

    /** Normal appearances for a checkbox: an empty /Off and a ticked /Yes. */
    private PDAppearanceDictionary checkBoxStates(PDDocument document, PDAnnotationWidget widget)
        throws IOException {

        PDRectangle box = widget.getRectangle();
        COSDictionary states = new COSDictionary();
        states.setItem(COSName.getPDFName(CHECKBOX_OFF_STATE), blankAppearance(document, box));
        states.setItem(COSName.getPDFName(CHECKBOX_ON_STATE),  tickAppearance(document, box));

        PDAppearanceDictionary appearance = new PDAppearanceDictionary();
        appearance.getCOSObject().setItem(COSName.N, states);
        return appearance;
    }

    private PDAppearanceStream blankAppearance(PDDocument document, PDRectangle box) {
        return newAppearanceStream(document, box);
    }

    /**
     * The ticked state, drawn as two strokes rather than a font glyph.
     *
     * <p>The conventional ZapfDingbats check is not encodable through
     * PDFBox's ZapfDingbats encoding, and a tick is two lines — reaching for
     * a font at all was the more complicated option.
     */
    private PDAppearanceStream tickAppearance(PDDocument document, PDRectangle box)
        throws IOException {

        PDAppearanceStream stream = newAppearanceStream(document, box);
        float width  = box.getWidth();
        float height = box.getHeight();

        try (PDPageContentStream content = new PDPageContentStream(
                document, stream, stream.getStream().createOutputStream())) {
            content.setStrokingColor(0f, 0f, 0f);
            content.setLineWidth(Math.max(1f, Math.min(width, height) * 0.12f));
            content.setLineCapStyle(1);   // round, so the corner reads cleanly
            content.moveTo(width * 0.22f, height * 0.52f);
            content.lineTo(width * 0.42f, height * 0.28f);
            content.lineTo(width * 0.78f, height * 0.74f);
            content.stroke();
        }
        return stream;
    }

    private PDAppearanceStream newAppearanceStream(PDDocument document, PDRectangle box) {
        PDAppearanceStream stream = new PDAppearanceStream(document);
        stream.setBBox(new PDRectangle(box.getWidth(), box.getHeight()));
        stream.setResources(new PDResources());
        return stream;
    }

    private PDTextField textField(PDAcroForm form, FieldPlacement placement) {
        PDTextField field = new PDTextField(form);
        field.setMultiline(placement.kind() == FieldKind.TEXTAREA);
        return field;
    }

    private PDComboBox dropdown(PDAcroForm form, FieldPlacement placement) {
        PDComboBox field = new PDComboBox(form);
        field.setOptions(placement.options());
        return field;
    }

    /** A visible border, so a placed field can be seen before it is filled. */
    private PDAppearanceCharacteristicsDictionary borderAndBackground() {
        PDAppearanceCharacteristicsDictionary appearance =
            new PDAppearanceCharacteristicsDictionary(new COSDictionary());
        appearance.setBorderColour(new org.apache.pdfbox.pdmodel.graphics.color.PDColor(
            new float[]{0.4f, 0.4f, 0.4f},
            org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB.INSTANCE));
        appearance.setBackground(new org.apache.pdfbox.pdmodel.graphics.color.PDColor(
            new float[]{0.96f, 0.97f, 1.0f},
            org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB.INSTANCE));
        return appearance;
    }
}
