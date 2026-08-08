package com.cde.platform.service;

import com.cde.platform.service.DigitalSignatureService.SelfSignedCert;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.Calendar;
import java.util.List;
import java.util.Optional;

/**
 * Writes a digital signature into the PDF itself.
 *
 * <p>Signing previously recorded a hash and a CMS blob in the database and
 * left the file untouched. That is a real signature, but only this
 * application can see it: the document opens in Acrobat showing no signature
 * at all, and a recipient outside the system has nothing to verify. A
 * signature that cannot travel with the document does not do the job
 * signatures exist for.
 *
 * <p>The signature is added as an incremental update, so every byte of the
 * original document is preserved and the {@code /ByteRange} covers the whole
 * file except the signature container — which is what lets any conforming
 * reader check it.
 */
@Service
public class PdfSignatureEmbedder {

    private static final Logger log = LoggerFactory.getLogger(PdfSignatureEmbedder.class);

    private static final String SIGNING_ALGORITHM = "SHA256withRSA";

    /** Details written into the signature dictionary and shown by readers. */
    public record SigningDetails(String signerName, String reason, String location) {}

    /**
     * Signs {@code source} and writes the signed document to {@code target}.
     *
     * @throws IOException if the document cannot be read, signed or written
     */
    public void embed(Path source, Path target, SelfSignedCert cert, SigningDetails details)
        throws IOException {

        try (PDDocument document = Loader.loadPDF(source.toFile())) {
            PDSignature signature = new PDSignature();
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            // Detached PKCS#7: the signed bytes stay in the document and the
            // container holds only the signature, which is what PAdES and
            // every mainstream reader expect.
            signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
            signature.setName(details.signerName());
            signature.setReason(details.reason());
            signature.setLocation(details.location());
            signature.setSignDate(Calendar.getInstance());

            document.addSignature(signature, content -> sign(content, cert));

            // Incremental save: the original bytes are untouched and appended
            // to, so anything already signed stays verifiable.
            try (OutputStream out = Files.newOutputStream(target)) {
                document.saveIncremental(out);
            }
        }

        log.info("Embedded a signature for {} into {}", details.signerName(), target.getFileName());
    }

    /**
     * Reads the signature embedded in a document and checks it against the
     * bytes it covers.
     *
     * @return empty when the document carries no embedded signature at all
     */
    public Optional<EmbeddedSignatureCheck> verifyEmbedded(Path path) throws IOException {
        byte[] fileBytes = Files.readAllBytes(path);

        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            List<PDSignature> signatures = document.getSignatureDictionaries();
            if (signatures.isEmpty()) return Optional.empty();

            // The last signature covers the most recent state of the file.
            PDSignature signature = signatures.get(signatures.size() - 1);
            byte[] signed    = signature.getSignedContent(fileBytes);
            byte[] container = signature.getContents(fileBytes);

            return Optional.of(check(signature, signed, container));
        }
    }

    /** Outcome of checking a signature found inside a document. */
    public record EmbeddedSignatureCheck(
        boolean valid,
        String  signerName,
        String  reason,
        String  message
    ) {}

    private EmbeddedSignatureCheck check(PDSignature signature, byte[] signed, byte[] container) {
        try {
            CMSSignedData signedData = new CMSSignedData(new CMSProcessableByteArray(signed), container);
            SignerInformation signer = signedData.getSignerInfos().getSigners().iterator().next();

            @SuppressWarnings("unchecked")
            var matches = signedData.getCertificates().getMatches(signer.getSID());
            if (matches.isEmpty()) {
                return new EmbeddedSignatureCheck(false, signature.getName(), signature.getReason(),
                    "The signature does not carry the certificate that produced it.");
            }

            X509Certificate certificate = new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
                .getCertificate((org.bouncycastle.cert.X509CertificateHolder) matches.iterator().next());

            boolean valid = signer.verify(
                new JcaSimpleSignerInfoVerifierBuilder().build(certificate));

            return new EmbeddedSignatureCheck(valid, signature.getName(), signature.getReason(),
                valid ? "The embedded signature is valid."
                      : "The document has been modified since it was signed.");

        } catch (CMSException | org.bouncycastle.operator.OperatorCreationException
                 | java.security.cert.CertificateException e) {
            // A malformed container is a failed verification, not a server
            // error — the caller asked whether the signature holds, and it
            // does not.
            log.warn("Embedded signature could not be checked: {}", e.getMessage());
            return new EmbeddedSignatureCheck(false, signature.getName(), signature.getReason(),
                "The embedded signature could not be read.");
        }
    }

    /** Produces the detached PKCS#7 container over the bytes PDFBox hands us. */
    private byte[] sign(InputStream content, SelfSignedCert cert) throws IOException {
        try {
            CMSSignedDataGenerator generator = new CMSSignedDataGenerator();
            ContentSigner signer = new JcaContentSignerBuilder(SIGNING_ALGORITHM)
                .build(cert.privateKey());

            generator.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(
                new JcaDigestCalculatorProviderBuilder().build())
                .build(signer, cert.certificate()));
            generator.addCertificates(new JcaCertStore(List.of(cert.certificate())));

            CMSSignedData signedData = generator.generate(
                new CMSProcessableByteArray(content.readAllBytes()), false);
            return signedData.getEncoded();

        } catch (Exception e) {
            throw new IOException("The signature container could not be built.", e);
        }
    }
}
