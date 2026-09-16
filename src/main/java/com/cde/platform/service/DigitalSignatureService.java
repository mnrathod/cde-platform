package com.cde.platform.service;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.springframework.stereotype.Service;

import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * DigitalSignatureService
 * Provides:
 *   1. Self-signed certificate generation (for development/testing)
 *   2. PDF signing metadata — embeds signature block into PDF
 *   3. Signature verification
 *   4. Signature record storage
 *
 * Production use: replace self-signed cert with CA-issued X.509 certificate.
 */
@Service
public class DigitalSignatureService {

    /**
     * How the signing time is written on the stamp.
     *
     * <p>Formatted explicitly because the previous version took
     * {@code signedAt.toString().substring(0, 19)}, and
     * {@code LocalDateTime.toString()} is not a fixed width: it omits the
     * seconds when they are zero and the nanoseconds when those are zero. A
     * signing timestamp that lands on a whole second — which a clock with
     * millisecond or coarser resolution produces routinely, and which is also
     * what comes back from a column stored at second precision — yields a
     * 16-character string and the substring throws. The failure is on the
     * signing path, after the document has already been written.
     */
    private static final java.time.format.DateTimeFormatter STAMP_TIMESTAMP =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Generate a self-signed X.509 certificate for a user.
     * In production, this would be replaced by a CA-signed certificate.
     */
    public SelfSignedCert generateSelfSignedCert(String commonName, String organisation) throws Exception {
        // Generate RSA key pair
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, new SecureRandom());
        KeyPair keyPair = kpg.generateKeyPair();

        // Certificate validity: 1 year
        Date notBefore = new Date();
        Date notAfter  = new Date(System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000);

        X500Name subject = new X500Name(
            String.format("CN=%s, O=%s, C=GB", commonName, organisation)
        );

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
            .build(keyPair.getPrivate());

        X509CertificateHolder holder = new JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(System.currentTimeMillis()),
            notBefore, notAfter,
            subject,
            keyPair.getPublic()
        ).build(signer);

        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(holder);

        return new SelfSignedCert(
            keyPair.getPrivate(),
            keyPair.getPublic(),
            cert,
            Base64.getEncoder().encodeToString(cert.getEncoded()),
            commonName
        );
    }

    /**
     * Create a signature record for a document.
     * The signature contains: document hash, signer info, timestamp, certificate thumbprint.
     * This is stored in the database as metadata — full PDF signing requires a PDF library.
     */
    public SignatureRecord createSignatureRecord(
        byte[]         documentBytes,
        String         signerName,
        String         signerEmail,
        String         role,           // "Author", "Reviewer", "Approver"
        String         reason,
        String         location,
        SelfSignedCert cert
    ) throws Exception {
        // Hash the document
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(documentBytes);
        String hashB64 = Base64.getEncoder().encodeToString(hash);

        // Sign the hash
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(cert.privateKey());
        sig.update(hash);
        byte[] signatureBytes = sig.sign();
        String signatureB64 = Base64.getEncoder().encodeToString(signatureBytes);

        return new SignatureRecord(
            UUID.randomUUID().toString(),
            signerName, signerEmail, role,
            reason, location,
            hashB64, signatureB64,
            cert.certificateB64(),
            LocalDateTime.now(),
            "SHA256withRSA",
            "VALID"
        );
    }

    /**
     * Verify a signature record against document bytes.
     */
    public VerificationResult verifySignature(
        byte[]          documentBytes,
        SignatureRecord record
    ) {
        try {
            // Reconstruct certificate
            byte[] certBytes = Base64.getDecoder().decode(record.certificateB64());
            X509Certificate cert = (X509Certificate) java.security.cert.CertificateFactory
                .getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(certBytes));

            // Check certificate validity
            cert.checkValidity();

            // Recompute document hash
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(documentBytes);
            String actualHash = Base64.getEncoder().encodeToString(hash);

            if (!actualHash.equals(record.documentHash())) {
                return new VerificationResult(false, "TAMPERED",
                    "Document has been modified since signing.");
            }

            // Verify signature
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(cert.getPublicKey());
            sig.update(hash);
            boolean valid = sig.verify(Base64.getDecoder().decode(record.signatureB64()));

            return valid
                ? new VerificationResult(true, "VALID", "Signature is valid.")
                : new VerificationResult(false, "INVALID", "Signature verification failed.");

        } catch (Exception e) {
            return new VerificationResult(false, "ERROR", e.getMessage());
        }
    }

    /**
     * Draws the visual signature stamp that is embedded into the document.
     *
     * <p>Every interpolated value is XML-escaped by {@link #escapeXml}. Three
     * of them — the signer's name, the capacity signed in, and the reason —
     * are text a person typed, and this builds markup by concatenation, which
     * §5.12 A03 names as injection whether the target is SQL or a template.
     * An ampersand in a company name was enough to produce a document whose
     * stamp would not parse; a reason containing a closing tag could put
     * chosen markup inside a signed document, which is a worse thing to be
     * wrong about than most, because the stamp is the part a reader treats as
     * evidence.
     *
     * <p>The viewer does not render this. It builds its preview from the
     * signature record, because Angular's sanitiser strips SVG and the
     * alternative is a banned bypass — see `DocumentSignatureComponent`.
     */
    public String generateSignatureStampSvg(SignatureRecord record) {
        String signedAt = STAMP_TIMESTAMP.format(record.signedAt());
        return String.format("""
            <svg xmlns="http://www.w3.org/2000/svg" width="240" height="70">
              <rect width="240" height="70" fill="#f0f8ff" stroke="#1e5fbe" stroke-width="1.5" rx="4"/>
              <text x="8" y="18" font-family="Arial" font-size="9" font-weight="bold" fill="#1e5fbe">DIGITALLY SIGNED</text>
              <line x1="8" y1="22" x2="232" y2="22" stroke="#1e5fbe" stroke-width="0.5"/>
              <text x="8" y="34" font-family="Arial" font-size="8" fill="#333">Signed by: %s</text>
              <text x="8" y="45" font-family="Arial" font-size="8" fill="#333">Role: %s | %s</text>
              <text x="8" y="56" font-family="Arial" font-size="8" fill="#666">Date: %s</text>
              <text x="8" y="67" font-family="Arial" font-size="7" fill="#999">Ref: %s</text>
            </svg>
            """,
            escapeXml(record.signerName()), escapeXml(record.role()), escapeXml(record.reason()),
            escapeXml(signedAt), escapeXml(record.id().substring(0, 8).toUpperCase())
        );
    }

    /**
     * Escapes text for use as XML character data or an attribute value.
     *
     * <p>Written out rather than pulled from a library because the five
     * predefined XML entities are the whole of the job, and an HTML escaper is
     * the wrong tool: HTML defines named entities such as {@code &nbsp;} that
     * XML does not, so escaping with one can produce a document an XML parser
     * rejects — which for an SVG being drawn into a PDF means a stamp that
     * silently fails to render.
     *
     * <p>Null becomes empty. A missing reason should leave a blank line on the
     * stamp, not the word "null" in a signed document.
     */
    private static String escapeXml(String text) {
        if (text == null) return "";
        StringBuilder escaped = new StringBuilder(text.length() + 16);
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '&'  -> escaped.append("&amp;");
                case '<'  -> escaped.append("&lt;");
                case '>'  -> escaped.append("&gt;");
                case '"'  -> escaped.append("&quot;");
                case '\'' -> escaped.append("&apos;");
                default   -> escaped.append(character);
            }
        }
        return escaped.toString();
    }

    // ── Value types ───────────────────────────────────────────────
    public record SelfSignedCert(
        PrivateKey     privateKey,
        PublicKey      publicKey,
        X509Certificate certificate,
        String          certificateB64,
        String          commonName
    ) {}

    public record SignatureRecord(
        String         id,
        String         signerName,
        String         signerEmail,
        String         role,
        String         reason,
        String         location,
        String         documentHash,
        String         signatureB64,
        String         certificateB64,
        LocalDateTime  signedAt,
        String         algorithm,
        String         status
    ) {}

    public record VerificationResult(
        boolean valid,
        String  status,
        String  message
    ) {}
}
