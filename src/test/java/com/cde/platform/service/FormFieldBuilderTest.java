package com.cde.platform.service;

import com.cde.platform.service.FormFieldBuilder.FieldKind;
import com.cde.platform.service.FormFieldBuilder.FieldPlacement;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Filling only ever worked on documents that already had fields. These cover
 * the other half — making a flat PDF fillable — and assert on the written
 * file, because a field that exists in the form's list but has no widget on
 * the page is invisible to whoever has to fill it in.
 */
class FormFieldBuilderTest {

    @TempDir Path workspace;

    private FormFieldBuilder builder;
    private Path             plain;

    @BeforeEach
    void setUp() throws IOException {
        builder = new FormFieldBuilder();
        plain   = workspace.resolve("plain.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.addPage(new PDPage());
            document.save(plain.toFile());
        }
    }

    private static FieldPlacement text(String name) {
        return new FieldPlacement(name, FieldKind.TEXT, 1, 70, 700, 200, 20, false, List.of());
    }

    private Path add(FieldPlacement... placements) throws IOException {
        Path out = workspace.resolve("with-fields.pdf");
        builder.addFields(plain, out, List.of(placements));
        return out;
    }

    @Test
    @DisplayName("a plain PDF gains a form")
    void createsAFormWhereThereWasNone() throws IOException {
        try (PDDocument before = Loader.loadPDF(plain.toFile())) {
            assertThat(before.getDocumentCatalog().getAcroForm()).isNull();
        }

        try (PDDocument after = Loader.loadPDF(add(text("inspector")).toFile())) {
            PDAcroForm form = after.getDocumentCatalog().getAcroForm();
            assertThat(form).isNotNull();
            assertThat(form.getField("inspector")).isNotNull();
        }
    }

    @Test
    @DisplayName("the field is on the page, not only in the form's list")
    void placesAWidgetOnThePage() throws IOException {
        // A field with no widget is invisible to whoever has to fill it in.
        try (PDDocument document = Loader.loadPDF(add(text("inspector")).toFile())) {
            assertThat(document.getPage(0).getAnnotations()).hasSize(1);

            var widget = document.getDocumentCatalog().getAcroForm()
                .getField("inspector").getWidgets().get(0);
            assertThat(widget.getRectangle().getLowerLeftX()).isEqualTo(70);
            assertThat(widget.getRectangle().getWidth()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("the form carries a font, so typed text renders")
    void suppliesDefaultResources() throws IOException {
        // Without a default appearance and a matching resource, a viewer has
        // nothing to draw with and the field stays blank however much is typed.
        try (PDDocument document = Loader.loadPDF(add(text("inspector")).toFile())) {
            PDAcroForm form = document.getDocumentCatalog().getAcroForm();
            assertThat(form.getDefaultAppearance()).contains("Helv");
            assertThat(form.getDefaultResources()).isNotNull();
        }
    }

    @Test
    @DisplayName("each kind becomes the right control")
    void createsEachKind() throws IOException {
        Path out = add(
            text("notes_single"),
            new FieldPlacement("notes_long", FieldKind.TEXTAREA, 1, 70, 600, 200, 60, false, List.of()),
            new FieldPlacement("passed",     FieldKind.CHECKBOX, 1, 70, 560, 16, 16, false, List.of()),
            new FieldPlacement("severity",   FieldKind.DROPDOWN, 1, 70, 520, 120, 20, false,
                List.of("Low", "Medium", "High")));

        try (PDDocument document = Loader.loadPDF(out.toFile())) {
            PDAcroForm form = document.getDocumentCatalog().getAcroForm();
            assertThat(form.getField("notes_single")).isInstanceOf(PDTextField.class);
            assertThat(((PDTextField) form.getField("notes_long")).isMultiline()).isTrue();
            assertThat(form.getField("passed")).isInstanceOf(PDCheckBox.class);
            assertThat(((PDComboBox) form.getField("severity")).getOptions())
                .containsExactly("Low", "Medium", "High");
        }
    }

    @Test
    @DisplayName("fields land on the page they were placed on")
    void honoursThePageNumber() throws IOException {
        Path out = add(
            text("on_page_one"),
            new FieldPlacement("on_page_two", FieldKind.TEXT, 2, 70, 700, 200, 20, false, List.of()));

        try (PDDocument document = Loader.loadPDF(out.toFile())) {
            assertThat(document.getPage(0).getAnnotations()).hasSize(1);
            assertThat(document.getPage(1).getAnnotations()).hasSize(1);
        }
    }

    @Test
    @DisplayName("required is recorded")
    void marksRequiredFields() throws IOException {
        Path out = add(new FieldPlacement(
            "inspector", FieldKind.TEXT, 1, 70, 700, 200, 20, true, List.of()));

        try (PDDocument document = Loader.loadPDF(out.toFile())) {
            assertThat(document.getDocumentCatalog().getAcroForm()
                .getField("inspector").isRequired()).isTrue();
        }
    }

    @Test
    @DisplayName("adding to a document that already has a form keeps the existing fields")
    void addsToAnExistingForm() throws IOException {
        Path first  = add(text("inspector"));
        Path second = workspace.resolve("two.pdf");
        builder.addFields(first, second, List.of(
            new FieldPlacement("approver", FieldKind.TEXT, 1, 70, 660, 200, 20, false, List.of())));

        try (PDDocument document = Loader.loadPDF(second.toFile())) {
            PDAcroForm form = document.getDocumentCatalog().getAcroForm();
            assertThat(form.getField("inspector")).isNotNull();
            assertThat(form.getField("approver")).isNotNull();
        }
    }

    @Test
    @DisplayName("every placed field carries an appearance stream")
    void givesEachFieldAnAppearance() throws IOException {
        // A field without /AP is legal but unusable: it renders as nothing,
        // and the form-filling path fails outright on the missing key.
        Path out = add(
            text("notes"),
            new FieldPlacement("passed",   FieldKind.CHECKBOX, 1, 70, 560, 16, 16, false, List.of()),
            new FieldPlacement("severity", FieldKind.DROPDOWN, 1, 70, 520, 120, 20, false,
                List.of("Low", "High")));

        try (PDDocument document = Loader.loadPDF(out.toFile())) {
            PDAcroForm form = document.getDocumentCatalog().getAcroForm();
            for (String name : List.of("notes", "passed", "severity")) {
                assertThat(form.getField(name).getWidgets().get(0).getAppearance())
                    .describedAs("appearance for '%s'", name)
                    .isNotNull();
            }
        }
    }

    @Test
    @DisplayName("a duplicate name is refused")
    void refusesDuplicateNames() throws IOException {
        Path existing = add(text("inspector"));

        // Two fields sharing a name share a value in the PDF spec, which is
        // almost never what someone drawing a second box meant.
        assertThatThrownBy(() -> builder.addFields(
                existing, workspace.resolve("out.pdf"), List.of(text("inspector"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("inspector");
    }

    @Test
    @DisplayName("a field off the end of the document is refused by page number")
    void refusesAPageThatDoesNotExist() {
        FieldPlacement offTheEnd =
            new FieldPlacement("x", FieldKind.TEXT, 9, 70, 700, 200, 20, false, List.of());

        assertThatThrownBy(() -> builder.addFields(plain, workspace.resolve("o.pdf"), List.of(offTheEnd)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("page 9");
    }

    @Test
    @DisplayName("a field with no area is refused")
    void refusesAnEmptyRectangle() {
        FieldPlacement flat =
            new FieldPlacement("x", FieldKind.TEXT, 1, 70, 700, 0, 20, false, List.of());

        assertThatThrownBy(() -> builder.addFields(plain, workspace.resolve("o.pdf"), List.of(flat)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a dropdown with no options is refused")
    void refusesAnEmptyDropdown() {
        FieldPlacement empty = new FieldPlacement(
            "severity", FieldKind.DROPDOWN, 1, 70, 700, 120, 20, false, List.of());

        assertThatThrownBy(() -> builder.addFields(plain, workspace.resolve("o.pdf"), List.of(empty)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("option");
    }

    @Test
    @DisplayName("an unnamed field is refused")
    void refusesABlankName() {
        FieldPlacement unnamed =
            new FieldPlacement("  ", FieldKind.TEXT, 1, 70, 700, 200, 20, false, List.of());

        assertThatThrownBy(() -> builder.addFields(plain, workspace.resolve("o.pdf"), List.of(unnamed)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("removing a field takes its widget off the page too")
    void removesFieldAndWidget() throws IOException {
        Path withFields = add(text("inspector"),
            new FieldPlacement("approver", FieldKind.TEXT, 1, 70, 660, 200, 20, false, List.of()));

        Path out = workspace.resolve("removed.pdf");
        assertThat(builder.removeFields(withFields, out, List.of("inspector")))
            .containsExactly("inspector");

        try (PDDocument document = Loader.loadPDF(out.toFile())) {
            PDAcroForm form = document.getDocumentCatalog().getAcroForm();
            assertThat(form.getField("inspector")).isNull();
            assertThat(form.getField("approver")).isNotNull();
            // A widget left behind would draw a box belonging to no field.
            assertThat(document.getPage(0).getAnnotations()).hasSize(1);
        }
    }

    @Test
    @DisplayName("removing a name that is not there is skipped, not an error")
    void ignoresUnknownNamesOnRemoval() throws IOException {
        Path withFields = add(text("inspector"));

        assertThat(builder.removeFields(withFields, workspace.resolve("o.pdf"),
            List.of("nonexistent", "inspector"))).containsExactly("inspector");
    }

    @Test
    @DisplayName("removing from a document with no form is not an error")
    void removingFromAPlainPdf() throws IOException {
        assertThat(builder.removeFields(plain, workspace.resolve("o.pdf"), List.of("anything")))
            .isEmpty();
    }
}
