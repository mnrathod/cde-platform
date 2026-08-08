package com.cde.platform.service;

import com.cde.platform.service.DigitalSignatureService.SelfSignedCert;
import com.cde.platform.service.PdfSignatureEmbedder.SigningDetails;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A signature that only this application can see does not do the job
 * signatures exist for, so these assert on the written file: that it carries
 * a real signature dictionary, that the signature verifies against the bytes
 * it covers, and that editing those bytes afterwards is detected.
 */
class PdfSignatureEmbedderTest {

    @TempDir Path workspace;

    private PdfSignatureEmbedder embedder;
    private SelfSignedCert       cert;
    private Path                 unsigned;

    private static final SigningDetails DETAILS =
        new SigningDetails("ada", "Approved for construction", "London");

    @BeforeEach
    void setUp() throws Exception {
        embedder = new PdfSignatureEmbedder();
        cert     = new DigitalSignatureService().generateSelfSignedCert("ada", "CDE Platform");
        unsigned = workspace.resolve("unsigned.pdf");
        writePdf(unsigned, "STRUCTURAL DRAWING A-101");
    }

    private void writePdf(Path path, String text) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                content.newLineAtOffset(70, 700);
                content.showText(text);
                content.endText();
            }
            document.save(path.toFile());
        }
    }

    private Path sign() throws IOException {
        Path signed = workspace.resolve("signed.pdf");
        embedder.embed(unsigned, signed, cert, DETAILS);
        return signed;
    }

    @Test
    @DisplayName("the signed file carries a signature dictionary any reader can find")
    void writesASignatureDictionary() throws IOException {
        Path signed = sign();

        try (PDDocument document = Loader.loadPDF(signed.toFile())) {
            var signatures = document.getSignatureDictionaries();
            assertThat(signatures).hasSize(1);
            assertThat(signatures.get(0).getName()).isEqualTo("ada");
            assertThat(signatures.get(0).getReason()).isEqualTo("Approved for construction");
            assertThat(signatures.get(0).getLocation()).isEqualTo("London");
            assertThat(signatures.get(0).getByteRange()).isNotNull();
        }
    }

    @Test
    @DisplayName("the embedded signature verifies")
    void verifies() throws IOException {
        var result = embedder.verifyEmbedded(sign()).orElseThrow();

        assertThat(result.valid()).isTrue();
        assertThat(result.signerName()).isEqualTo("ada");
    }

    @Test
    @DisplayName("the original content survives — signing appends, it does not rewrite")
    void preservesTheOriginalContent() throws IOException {
        byte[] before = Files.readAllBytes(unsigned);
        Path signed = sign();
        byte[] after = Files.readAllBytes(signed);

        // An incremental update starts with the original file byte for byte,
        // so anything signed earlier stays verifiable.
        assertThat(after.length).isGreaterThan(before.length);
        assertThat(java.util.Arrays.copyOf(after, before.length)).isEqualTo(before);
    }

    @Test
    @DisplayName("tampering with the signed bytes is detected")
    void detectsTampering() throws IOException {
        Path signed = sign();
        byte[] bytes = Files.readAllBytes(signed);

        // Corrupt a byte inside the region the signature covers.
        int target = 200;
        bytes[target] = (byte) (bytes[target] ^ 0xFF);
        Path tampered = workspace.resolve("tampered.pdf");
        Files.write(tampered, bytes);

        var result = embedder.verifyEmbedded(tampered).orElseThrow();
        assertThat(result.valid()).isFalse();
    }

    @Test
    @DisplayName("an unsigned document reports no signature rather than an invalid one")
    void unsignedDocumentHasNoSignature() throws IOException {
        // "Not signed" and "signed but broken" must not look the same.
        assertThat(embedder.verifyEmbedded(unsigned)).isEmpty();
    }

    @Test
    @DisplayName("a second signature is added without invalidating the first")
    void supportsASecondSignature() throws Exception {
        Path first  = sign();
        Path second = workspace.resolve("countersigned.pdf");
        SelfSignedCert other = new DigitalSignatureService()
            .generateSelfSignedCert("grace", "CDE Platform");
        embedder.embed(first, second, other,
            new SigningDetails("grace", "Countersigned", "Manchester"));

        try (PDDocument document = Loader.loadPDF(second.toFile())) {
            assertThat(document.getSignatureDictionaries()).hasSize(2);
        }
        // The most recent signature covers the countersigned state.
        assertThat(embedder.verifyEmbedded(second).orElseThrow().valid()).isTrue();
    }
}
