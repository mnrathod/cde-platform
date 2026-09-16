package com.cde.platform.service;

import com.cde.platform.service.DigitalSignatureService.SignatureRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stamp drawn into a signed document, built from text a person typed.
 *
 * <p>{@code generateSignatureStampSvg} assembles markup by concatenation, and
 * three of the values it interpolates are free text: the signer's name, the
 * capacity they signed in, and the reason. §5.12 A03 treats that as injection
 * regardless of whether the target is SQL or a template, and the consequences
 * here are not theoretical in either direction. An ampersand — ordinary in a
 * company name — produces markup no XML parser will accept, so the stamp
 * silently fails to draw. A closing tag puts chosen markup inside a signed
 * document, which is a worse thing to be wrong about than most, because the
 * stamp is the part a reader treats as evidence of who approved what.
 *
 * <p>So these assert by parsing rather than by string matching: the output has
 * to be well-formed, and the text a user supplied has to come back as text
 * rather than as structure. A {@code contains("&amp;")} check would pass on
 * output that was still broken in some other way.
 */
class SignatureStampMarkupTest {

    private final DigitalSignatureService signatures = new DigitalSignatureService();

    /** A record carrying whatever a signer typed, with everything else fixed. */
    private static SignatureRecord signedBy(String name, String role, String reason) {
        return new SignatureRecord(
            "ab12cd34-0000-0000-0000-000000000000",
            name, "signer@example.invalid", role, reason,
            "Site office", "hash", "sig", "cert",
            LocalDateTime.of(2026, 3, 4, 9, 30, 0),
            "SHA256withRSA", "VALID");
    }

    /**
     * Parses the stamp, refusing DTDs and external entities.
     *
     * <p>Configured per §5.13.9 rather than left at the defaults, because a
     * test parser that resolves entities would be a second copy of the problem
     * this file exists to check for.
     */
    private static Document parse(String svg) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // The stamp declares the SVG namespace, so without this the parser
        // reports no local names and finds no elements by namespace — which
        // reads as "the markup is broken" rather than "the test is".
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(svg)));
    }

    /** Every bit of text in the parsed stamp, joined. */
    private static String textOf(Document stamp) {
        return stamp.getDocumentElement().getTextContent();
    }

    @Test
    @DisplayName("an ordinary name produces a stamp that parses")
    void parsesForOrdinaryInput() throws Exception {
        Document stamp = parse(signatures.generateSignatureStampSvg(
            signedBy("J. Okafor", "Approver", "Approved for construction")));

        assertThat(stamp.getDocumentElement().getLocalName()).isEqualTo("svg");
        assertThat(textOf(stamp)).contains("J. Okafor", "Approver", "Approved for construction");
    }

    @Test
    @DisplayName("an ampersand in a name does not break the stamp")
    void survivesAnAmpersand() throws Exception {
        // The case that makes this a live defect rather than a hypothetical:
        // partnerships are named this way constantly.
        Document stamp = parse(signatures.generateSignatureStampSvg(
            signedBy("Marsh & Okafor LLP", "Approver", "Issued for tender")));

        assertThat(textOf(stamp)).contains("Marsh & Okafor LLP");
    }

    @Test
    @DisplayName("markup in the reason is drawn as text, not as elements")
    void doesNotLetTheReasonAddElements() throws Exception {
        String injection = "</text><text x=\"8\" y=\"34\">APPROVED BY THE ENGINEER</text><text>";

        Document stamp = parse(signatures.generateSignatureStampSvg(
            signedBy("J. Okafor", "Reviewer", injection)));

        // The count is the assertion. The template draws five <text> elements;
        // if the reason had escaped into structure there would be more, and a
        // line the signer never wrote would appear on a signed document.
        NodeList drawn = stamp.getElementsByTagNameNS("*", "text");
        assertThat(drawn.getLength()).isEqualTo(5);
        assertThat(textOf(stamp)).contains(injection);
    }

    @Test
    @DisplayName("an attribute payload in the role stays inert text")
    void keepsAnAttributePayloadAsText() throws Exception {
        // Every value in this template lands in element content, not in an
        // attribute, so quotes are already harmless — this passes with the
        // escaping removed, and is kept as the guard for the day someone
        // interpolates a value into an attribute and quotes start to matter.
        Document stamp = parse(signatures.generateSignatureStampSvg(
            signedBy("J. Okafor", "\" onload=\"alert(1)", "Reviewed")));

        assertThat(stamp.getDocumentElement().hasAttribute("onload")).isFalse();
        assertThat(textOf(stamp)).contains("\" onload=\"alert(1)");
    }

    @Test
    @DisplayName("a timestamp on a whole second still produces a stamp")
    void survivesAWholeSecondTimestamp() {
        // LocalDateTime.toString() drops the seconds when they are zero, so the
        // previous substring(0, 19) threw for any signing that landed on a
        // whole second — routine on a millisecond-resolution clock, and
        // guaranteed for a value read back from a second-precision column. It
        // threw on the signing path, after the document had been written.
        SignatureRecord onTheSecond = signedBy("J. Okafor", "Approver", "Issued");

        assertThat(signatures.generateSignatureStampSvg(onTheSecond))
            .contains("2026-03-04 09:30:00");
    }

    @Test
    @DisplayName("a missing reason leaves a blank, not the word null")
    void writesNothingForAMissingReason() throws Exception {
        Document stamp = parse(signatures.generateSignatureStampSvg(
            signedBy("J. Okafor", "Reviewer", null)));

        assertThat(textOf(stamp)).doesNotContain("null");
    }
}
