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
     * Generate a visual signature stamp (for embedding in PDF/DXF viewer).
     * Returns an SVG string representing the digital signature block.
     */
    public String generateSignatureStampSvg(SignatureRecord record) {
        String ts = record.signedAt().toString().replace("T", " ").substring(0, 19);
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
            record.signerName(), record.role(), record.reason(),
            ts, record.id().substring(0, 8).toUpperCase()
        );
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
