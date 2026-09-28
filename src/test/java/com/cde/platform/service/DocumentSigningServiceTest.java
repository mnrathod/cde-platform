package com.cde.platform.service;

import com.cde.platform.model.Document;
import com.cde.platform.model.DocumentSignature;
import com.cde.platform.model.Project;
import com.cde.platform.model.User;
import com.cde.platform.repository.DocumentRepository;
import com.cde.platform.repository.DocumentSignatureRepository;
import com.cde.platform.repository.ProjectRepository;
import com.cde.platform.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Signing a document, and being able to tell afterwards that it is still the
 * document that was signed.
 *
 * <p>Two paths that must stay distinguishable. A PDF can carry a signature
 * inside itself, so any conforming reader can check it without this platform;
 * anything else cannot, and the record stands beside the file instead. The
 * outcome says which happened, and that flag is the whole basis on which a
 * client decides whether to tell someone the signature travels with the file.
 * Reporting a detached record as embedded would be a claim about a file that
 * does not hold.
 *
 * <p>The signing is real — real certificates, real PDFBox — because what is
 * being checked is that a signature survives being written and read back, and
 * a stubbed signer would verify its own stub. The database is real for the
 * same reason as everywhere else here: verification updates the stored status,
 * and that write is part of the behaviour.
 */
@SpringBootTest
@DisplayName("signing a document")
class DocumentSigningServiceTest {

    private static final String SIGNER = "signing-service-signer";
    private static final String OTHER  = "signing-service-other";

    @Autowired DocumentSigningService signing;
    @Autowired DocumentRepository documentRepo;
    @Autowired DocumentSignatureRepository signatureRepo;
    @Autowired ProjectRepository projectRepo;
    @Autowired UserRepository userRepo;

    @TempDir Path storage;

    private Project project;
    private User signer;

    @BeforeEach
    void setUp() {
        signer = user(SIGNER);
        user(OTHER);
        project = projectRepo.save(Project.builder()
            .name("Signing " + System.nanoTime())
            .phase(Project.ProjectPhase.DESIGN).build());
    }

    private User user(String username) {
        return userRepo.findByUsername(username).orElseGet(() ->
            userRepo.save(User.builder()
                .username(username).email(username + "@example.test")
                .password("{noop}irrelevant").role(User.Role.REVIEWER).build()));
    }

    /** A one-page PDF, real enough for PDFBox to open and sign. */
    private Document pdf() throws IOException {
        Path path = storage.resolve(UUID.randomUUID() + ".pdf");
        try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            document.save(path.toFile());
        }
        return store(path, "drawing.pdf", "application/pdf");
    }

    /** Something no signature dictionary can live inside. */
    private Document plainText() throws IOException {
        Path path = storage.resolve(UUID.randomUUID() + ".txt");
        Files.writeString(path, "Issued for construction.");
        return store(path, "notes.txt", "text/plain");
    }

    private Document store(Path path, String fileName, String mediaType) throws IOException {
        return documentRepo.save(Document.builder()
            .name(fileName.replaceAll("\\.[^.]+$", ""))
            .fileName(fileName).fileType(mediaType)
            .filePath(path.toString()).fileSize(Files.size(path))
            .documentType(Document.DocumentType.DRAWING)
            .status(Document.DocumentStatus.IN_REVIEW)
            .project(project).uploadedBy(signer).build());
    }

    private DocumentSigningService.SignRequest asReviewer() {
        return new DocumentSigningService.SignRequest("Reviewer", "Checked", "Site office");
    }

    private DocumentSigningService.SignRequest asApprover() {
        return new DocumentSigningService.SignRequest("Approver", "Approved", "Head office");
    }

    @Nested
    @DisplayName("what the request means when it leaves things out")
    class RequestDefaults {

        @Test
        @DisplayName("an empty request signs as a reviewer")
        void defaultsToReviewer() {
            var request = new DocumentSigningService.SignRequest(null, null, null);

            assertThat(request.role()).isEqualTo("Reviewer");
        }

        @Test
        @DisplayName("blank fields are treated as absent, not stored as blanks")
        void blanksAreDefaulted() {
            // A signature block reading "signed as   for   at  " is worse than
            // one reading the defaults.
            var request = new DocumentSigningService.SignRequest("  ", "", "   ");

            assertThat(request.role()).isEqualTo("Reviewer");
            assertThat(request.reason()).isNotBlank();
            assertThat(request.location()).isNotBlank();
        }

        @Test
        @DisplayName("what was given is kept")
        void keepsWhatWasGiven() {
            var request = new DocumentSigningService.SignRequest(
                "Approver", "Approved for construction", "Site office");

            assertThat(request.role()).isEqualTo("Approver");
            assertThat(request.reason()).isEqualTo("Approved for construction");
            assertThat(request.location()).isEqualTo("Site office");
        }
    }

    @Nested
    @DisplayName("signing a PDF")
    class SigningAPdf {

        @Test
        @DisplayName("writes the signature into the document itself")
        void embedsTheSignature() throws Exception {
            var outcome = signing.sign(pdf().getId(), SIGNER, asReviewer());

            // The flag a client uses to decide whether to say the signature
            // travels with the file.
            assertThat(outcome.embedded()).isTrue();
        }

        @Test
        @DisplayName("commits the signed bytes as a new version")
        void commitsANewVersion() throws Exception {
            Document document = pdf();

            var outcome = signing.sign(document.getId(), SIGNER, asReviewer());

            // The version the signature covers has to be the one that contains
            // it: a PDF signature protects the file it lives in.
            assertThat(outcome.version()).isNotNull();
            assertThat(Path.of(outcome.version().getFilePath())).exists();
        }

        @Test
        @DisplayName("records who signed it")
        void recordsTheSigner() throws Exception {
            var outcome = signing.sign(pdf().getId(), SIGNER, asReviewer());

            assertThat(outcome.signature().getSignerName()).isEqualTo(SIGNER);
        }

        @Test
        @DisplayName("produces a stamp to show in the viewer")
        void producesAStamp() throws Exception {
            var outcome = signing.sign(pdf().getId(), SIGNER, asReviewer());

            assertThat(outcome.stampSvg()).contains("<svg");
        }

        @Test
        @DisplayName("verifies as valid straight afterwards")
        void verifiesAsValid() throws Exception {
            var outcome = signing.sign(pdf().getId(), SIGNER, asReviewer());

            var verification = signing.verify(outcome.signature().getSignatureId());

            assertThat(verification).isPresent();
            assertThat(verification.get().valid()).isTrue();
            assertThat(verification.get().status()).isEqualTo("VALID");
        }

        @Test
        @DisplayName("the check reads the signature from inside the file")
        void verificationIsEmbedded() throws Exception {
            var outcome = signing.sign(pdf().getId(), SIGNER, asReviewer());

            assertThat(signing.verify(outcome.signature().getSignatureId())
                .orElseThrow().embedded()).isTrue();
        }
    }

    @Nested
    @DisplayName("signing something that is not a PDF")
    class SigningOtherFormats {

        @Test
        @DisplayName("records the signature beside the file rather than refusing")
        void recordsADetachedSignature() throws Exception {
            var outcome = signing.sign(plainText().getId(), SIGNER, asReviewer());

            assertThat(outcome.signature()).isNotNull();
        }

        @Test
        @DisplayName("says the signature is not inside the file")
        void saysItIsNotEmbedded() throws Exception {
            // Nothing but a PDF can carry a signature dictionary, and implying
            // otherwise is a claim about the file that does not hold — a
            // recipient opening it elsewhere would find nothing.
            var outcome = signing.sign(plainText().getId(), SIGNER, asReviewer());

            assertThat(outcome.embedded()).isFalse();
        }

        @Test
        @DisplayName("still verifies, against the recorded hash")
        void verifiesAgainstTheRecord() throws Exception {
            var outcome = signing.sign(plainText().getId(), SIGNER, asReviewer());

            var verification = signing.verify(outcome.signature().getSignatureId());

            assertThat(verification).isPresent();
            // The defect this found: verification tried to read the file as a
            // PDF first, and the parse failure came back as "the signed
            // document could not be read" — so the detached fallback that
            // exists for exactly this case was never reached, and every
            // signature on anything but a PDF was unverifiable.
            assertThat(verification.get().valid())
                .as("a detached signature must verify: %s", verification.get().message())
                .isTrue();
            assertThat(verification.get().embedded()).isFalse();
        }

        @Test
        @DisplayName("notices when the file changed under it")
        void noticesTampering() throws Exception {
            // The point of a detached signature: the bytes are hashed at
            // signing time, so a later edit stops matching.
            Document document = plainText();
            var outcome = signing.sign(document.getId(), SIGNER, asReviewer());

            Files.writeString(Path.of(
                documentRepo.findById(document.getId()).orElseThrow().getFilePath()),
                "Issued for construction. Revised.", StandardCharsets.UTF_8);

            assertThat(signing.verify(outcome.signature().getSignatureId())
                .orElseThrow().valid()).isFalse();
        }

        @Test
        @DisplayName("a failed check is written back to the record, not just returned")
        void recordsTheFailedStatus() throws Exception {
            // Otherwise the list of a document's signatures keeps showing a
            // signature as good after a check has shown it is not.
            Document document = plainText();
            var outcome = signing.sign(document.getId(), SIGNER, asReviewer());
            Files.writeString(Path.of(
                documentRepo.findById(document.getId()).orElseThrow().getFilePath()), "changed");

            signing.verify(outcome.signature().getSignatureId());

            assertThat(signatureRepo.findBySignatureId(outcome.signature().getSignatureId())
                .orElseThrow().getStatus())
                .isNotEqualTo(DocumentSignature.SignatureStatus.VALID);
        }
    }

    @Nested
    @DisplayName("what signing does to the document's own status")
    class DocumentStatus {

        @Test
        @DisplayName("approving it marks it approved")
        void approvalApprovesTheDocument() throws Exception {
            Document document = pdf();

            var outcome = signing.sign(document.getId(), SIGNER, asApprover());

            assertThat(outcome.documentStatus())
                .isEqualTo(Document.DocumentStatus.APPROVED.name());
        }

        @Test
        @DisplayName("the approval is persisted, not only reported")
        void approvalIsPersisted() throws Exception {
            Document document = pdf();

            signing.sign(document.getId(), SIGNER, asApprover());

            assertThat(documentRepo.findById(document.getId()).orElseThrow().getStatus())
                .isEqualTo(Document.DocumentStatus.APPROVED);
        }

        @Test
        @DisplayName("the role is read without regard to case")
        void approverIsCaseInsensitive() throws Exception {
            Document document = pdf();

            signing.sign(document.getId(), SIGNER,
                new DocumentSigningService.SignRequest("approver", "ok", "here"));

            assertThat(documentRepo.findById(document.getId()).orElseThrow().getStatus())
                .isEqualTo(Document.DocumentStatus.APPROVED);
        }

        @Test
        @DisplayName("a reviewer's signature leaves the status where it was")
        void reviewingDoesNotApprove() throws Exception {
            // Checking a drawing and authorising it are different acts. A
            // reviewer's signature that silently approved would bypass the
            // approval step entirely.
            Document document = pdf();

            signing.sign(document.getId(), SIGNER, asReviewer());

            assertThat(documentRepo.findById(document.getId()).orElseThrow().getStatus())
                .isEqualTo(Document.DocumentStatus.IN_REVIEW);
        }
    }

    @Nested
    @DisplayName("withdrawing a signature")
    class Revoking {

        @Test
        @DisplayName("the signer may withdraw their own")
        void signerMayRevoke() throws Exception {
            var outcome = signing.sign(pdf().getId(), SIGNER, asReviewer());

            assertThat(signing.revoke(outcome.signature().getSignatureId(), SIGNER))
                .contains(true);
        }

        @Test
        @DisplayName("it is gone afterwards")
        void itIsGoneAfterwards() throws Exception {
            var outcome = signing.sign(pdf().getId(), SIGNER, asReviewer());
            String id = outcome.signature().getSignatureId();

            signing.revoke(id, SIGNER);

            assertThat(signatureRepo.findBySignatureId(id)).isEmpty();
        }

        @Test
        @DisplayName("somebody else may not withdraw it")
        void othersMayNotRevoke() throws Exception {
            // A signature is a person's attestation; another account removing
            // it would make the record meaningless.
            var outcome = signing.sign(pdf().getId(), SIGNER, asReviewer());

            assertThat(signing.revoke(outcome.signature().getSignatureId(), OTHER))
                .contains(false);
        }

        @Test
        @DisplayName("a refused withdrawal leaves the signature in place")
        void refusalLeavesItAlone() throws Exception {
            var outcome = signing.sign(pdf().getId(), SIGNER, asReviewer());
            String id = outcome.signature().getSignatureId();

            signing.revoke(id, OTHER);

            assertThat(signatureRepo.findBySignatureId(id)).isPresent();
        }

        @Test
        @DisplayName("withdrawing one that does not exist reports nothing rather than false")
        void unknownSignatureIsEmpty() {
            // Empty and false mean different things to the caller: one is a
            // 404, the other a 403.
            assertThat(signing.revoke("no-such-signature", SIGNER)).isEmpty();
        }
    }

    @Nested
    @DisplayName("when the request cannot be served")
    class Refusals {

        @Test
        @DisplayName("verifying a signature that does not exist reports nothing")
        void verifyingAnUnknownSignature() {
            assertThat(signing.verify("no-such-signature")).isEmpty();
        }

        @Test
        @DisplayName("signing as somebody who does not exist is refused")
        void unknownSignerIsRefused() throws Exception {
            Long documentId = pdf().getId();

            assertThatThrownBy(() -> signing.sign(documentId, "nobody", asReviewer()))
                .hasMessageContaining("signer");
        }

        @Test
        @DisplayName("signing a document that does not exist is refused")
        void unknownDocumentIsRefused() {
            assertThatThrownBy(() -> signing.sign(987_654_321L, SIGNER, asReviewer()))
                .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("a signature whose file has gone reports a failed check, not a crash")
        void missingFileIsAFailedCheck() throws Exception {
            // A restore or a storage fault is not a reason to answer a
            // verification request with a server error.
            Document document = plainText();
            var outcome = signing.sign(document.getId(), SIGNER, asReviewer());
            Files.delete(Path.of(
                documentRepo.findById(document.getId()).orElseThrow().getFilePath()));

            var verification = signing.verify(outcome.signature().getSignatureId());

            assertThat(verification).isPresent();
            assertThat(verification.get().valid()).isFalse();
        }
    }

    @Nested
    @DisplayName("listing what has been signed")
    class Listing {

        @Test
        @DisplayName("a document with no signatures lists none")
        void listsNothingForAnUnsignedDocument() throws Exception {
            assertThat(signing.signaturesFor(pdf().getId())).isEmpty();
        }

        @Test
        @DisplayName("every signature on the document is listed")
        void listsEverySignature() throws Exception {
            Document document = pdf();
            signing.sign(document.getId(), SIGNER, asReviewer());
            signing.sign(document.getId(), OTHER, asReviewer());

            assertThat(signing.signaturesFor(document.getId())).hasSize(2);
        }

        @Test
        @DisplayName("another document's signatures are not listed with them")
        void doesNotListAnotherDocumentsSignatures() throws Exception {
            Document signed = pdf();
            Document untouched = pdf();
            signing.sign(signed.getId(), SIGNER, asReviewer());

            assertThat(signing.signaturesFor(untouched.getId())).isEmpty();
        }
    }
}
