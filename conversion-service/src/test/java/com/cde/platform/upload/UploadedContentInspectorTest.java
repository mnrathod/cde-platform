package com.cde.platform.upload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the bytes say, versus what the upload claimed.
 *
 * <p>Every case here is one where the file name lies. That is the only case
 * worth testing: an attacker chooses the name, so a check against the name
 * checks nothing, and the whole point of this class is to ask the bytes
 * instead.
 */
class UploadedContentInspectorTest {

    private final UploadedContentInspector inspector = new UploadedContentInspector();

    @TempDir
    Path uploads;

    private Path fileOf(String name, byte[] content) throws IOException {
        Path path = uploads.resolve(name);
        Files.write(path, content);
        return path;
    }

    private Path fileOf(String name, String content) throws IOException {
        return fileOf(name, content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a Windows executable named as a model is refused")
    void refusesAnExecutableUnderAModelName() throws IOException {
        // "MZ" is the DOS header every Windows executable starts with. The
        // name says Revit model; the bytes say program.
        Path disguised = fileOf("site-model.rvt",
            new byte[] { 'M', 'Z', (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00 });

        var inspected = inspector.inspect(disguised, "site-model.rvt");

        assertThat(inspected.isRefused()).isTrue();
        assertThat(inspected.refusalReason()).contains("executable");
    }

    @Test
    @DisplayName("an ELF binary named as a drawing is refused")
    void refusesAnElfBinary() throws IOException {
        Path disguised = fileOf("plan.dwg",
            new byte[] { 0x7F, 'E', 'L', 'F', 0x02, 0x01, 0x01, 0x00 });

        assertThat(inspector.inspect(disguised, "plan.dwg").isRefused()).isTrue();
    }

    @Test
    @DisplayName("a real PDF is accepted and not marked active")
    void acceptsAPdf() throws IOException {
        Path pdf = fileOf("drawing.pdf", "%PDF-1.7\n%âãÏÓ\n");

        var inspected = inspector.inspect(pdf, "drawing.pdf");

        assertThat(inspected.isRefused()).isFalse();
        assertThat(inspected.detectedType()).isEqualTo("application/pdf");
        assertThat(inspected.activeContent()).isFalse();
    }

    @Test
    @DisplayName("a plain SVG is accepted but marked as active content")
    void acceptsAPlainSvgAsActiveContent() throws IOException {
        // A drawing package legitimately exports SVG, so this must not be
        // refused. It must be marked, because the viewer renders it as markup.
        Path svg = fileOf("plan.svg",
            "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect width=\"10\" height=\"10\"/></svg>");

        var inspected = inspector.inspect(svg, "plan.svg");

        assertThat(inspected.isRefused()).isFalse();
        assertThat(inspected.activeContent()).isTrue();
    }

    @Test
    @DisplayName("an SVG carrying a script is refused, not cleaned")
    void refusesScriptedSvg() throws IOException {
        Path svg = fileOf("plan.svg",
            "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>fetch('/api/projects')</script></svg>");

        var inspected = inspector.inspect(svg, "plan.svg");

        // Refused rather than sanitised. A small SVG is stored inline and
        // rendered as markup on the application's own origin, so a scripted one
        // is stored cross-site scripting executing with every viewer's session.
        // A CAD export has no scripts, so refusing costs a real upload nothing.
        assertThat(inspected.isRefused()).isTrue();
        assertThat(inspected.refusalReason()).contains("script");
    }

    @Test
    @DisplayName("an SVG with an event handler is refused too")
    void refusesEventHandlers() throws IOException {
        Path svg = fileOf("plan.svg",
            "<svg xmlns=\"http://www.w3.org/2000/svg\">"
            + "<image href=\"x\" onerror=\"alert(1)\"/></svg>");

        assertThat(inspector.inspect(svg, "plan.svg").isRefused()).isTrue();
    }

    @Test
    @DisplayName("markup named as a model is caught by its bytes")
    void catchesMarkupUnderAModelName() throws IOException {
        // Detection reports both a real IFC and an HTML page as text, so the
        // detected type alone does not separate them — which is why the leading
        // bytes are checked as well.
        Path disguised = fileOf("federated.ifc",
            "<html><body><script>alert(1)</script></body></html>");

        assertThat(inspector.inspect(disguised, "federated.ifc").isRefused()).isTrue();
    }

    @Test
    @DisplayName("a genuine IFC model is accepted")
    void acceptsAnIfcModel() throws IOException {
        // The formats this product exists for are exactly the ones detection
        // reports as plain text or generic binary. Refusing those would refuse
        // the product's own file types.
        Path ifc = fileOf("federated.ifc",
            "ISO-10303-21;\nHEADER;\nFILE_DESCRIPTION(('ViewDefinition'),'2;1');\nENDSEC;\n");

        var inspected = inspector.inspect(ifc, "federated.ifc");

        assertThat(inspected.isRefused()).isFalse();
        assertThat(inspected.activeContent()).isFalse();
    }

    @Test
    @DisplayName("only the header is read, whatever the file's size")
    void readsOnlyTheHeader() throws IOException {
        // A model is gigabytes. Identifying it must not mean reading it, or the
        // inspection undoes the streaming that keeps it off the heap.
        byte[] large = new byte[UploadedContentInspector.INSPECTED_BYTES * 4];
        large[0] = '%'; large[1] = 'P'; large[2] = 'D'; large[3] = 'F';
        large[4] = '-'; large[5] = '1'; large[6] = '.'; large[7] = '7';

        var inspected = inspector.inspect(fileOf("big.pdf", large), "big.pdf");

        assertThat(inspected.detectedType()).isEqualTo("application/pdf");
    }
}
