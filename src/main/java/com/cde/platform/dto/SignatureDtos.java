package com.cde.platform.dto;

import com.cde.platform.model.DocumentSignature;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * Digital signature payloads.
 *
 * <p>A signature attests to one revision of a document. It says nothing about
 * any later revision, which is why the version it covers is part of every
 * response rather than something a caller has to correlate.
 */
public final class SignatureDtos {

    private SignatureDtos() {
    }

    @Schema(name = "SignatureRequest",
            description = "Optional context recorded alongside a signature. The signer's identity "
                        + "comes from the authenticated principal and is not settable here.")
    public record SignatureRequest(

        @Schema(description = "Capacity the person is signing in.", example = "Lead Structural Engineer",
                maxLength = 120)
        @Size(max = 120) String role,

        @Schema(description = "Why the document is being signed.", example = "Approved for construction",
                maxLength = 240)
        @Size(max = 240) String reason,

        @Schema(description = "Where it was signed, as recorded in the signature dictionary.",
                example = "Manchester, UK", maxLength = 120)
        @Size(max = 120) String location
    ) {}

    @Schema(name = "SignatureResponse", description = "A signature held against a document.")
    public record SignatureResponse(

        @Schema(description = "Database identifier of the signature record.", example = "512",
                accessMode = Schema.AccessMode.READ_ONLY, requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,

        @Schema(description = "Stable public identifier, used to verify or revoke this signature.",
                example = "sig_7f3a91c4e2b8", requiredMode = Schema.RequiredMode.REQUIRED)
        String signatureId,

        @Schema(description = "Name of the person who signed, as held at the time of signing.",
                example = "J. Okafor", requiredMode = Schema.RequiredMode.REQUIRED)
        String signerName,

        @Schema(description = "Contact address of the signer, empty when none was held.",
                example = "j.okafor@example.test", format = "email")
        String signerEmail,

        @Schema(description = "Capacity the person signed in, empty when none was given.",
                example = "Lead Structural Engineer")
        String role,

        @Schema(description = "Why the document was signed, empty when none was given.",
                example = "Approved for construction")
        String reason,

        @Schema(description = "Whether the signature still stands.", example = "VALID",
                requiredMode = Schema.RequiredMode.REQUIRED)
        DocumentSignature.SignatureStatus status,

        @Schema(description = "When the document was signed, UTC.", example = "2026-02-21T10:33:09",
                format = "date-time", accessMode = Schema.AccessMode.READ_ONLY,
                requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDateTime signedAt,

        @Schema(description = "Version of the document this signature attests to. A signature on "
                            + "version 2 says nothing about version 5.",
                example = "2", minimum = "1")
        Integer version
    ) {}

    @Schema(name = "SigningResult",
            description = "What signing produced: the signature record, the version it committed, "
                        + "and whether the signature was written into the file itself.")
    public record SigningResult(

        @Schema(description = "The signature that was created.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        SignatureResponse signature,

        @Schema(description = "Visual stamp for the viewer to draw, as SVG markup. Presentation "
                            + "only — it is not what makes the signature valid.",
                example = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"220\" height=\"64\"/>")
        String stampSvg,

        @Schema(description = "Version number this signing committed.", example = "3", minimum = "1",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Integer version,

        @Schema(description = "Whether the signature was embedded in the document itself (PAdES), "
                            + "rather than only recorded here. An embedded signature can be "
                            + "verified by any conforming reader; a recorded one cannot.",
                example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean embedded,

        @Schema(description = "Review state the document now holds.", example = "APPROVED",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String documentStatus
    ) {}

    @Schema(name = "SignatureVerification", description = "The outcome of checking a signature.")
    public record SignatureVerification(

        @Schema(description = "Whether the signature checks out.", example = "true",
                requiredMode = Schema.RequiredMode.REQUIRED)
        boolean valid,

        @Schema(description = "Machine-readable outcome.", example = "VALID",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String status,

        @Schema(description = "What the outcome means, phrased for a person.",
                example = "Signed by J. Okafor on 21 February 2026. The document has not changed since.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String message,

        @Schema(description = "Whether the check read the document itself or only this "
                            + "application's record of the signature. The difference is whether a "
                            + "recipient outside this system could reach the same conclusion.",
                example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean embedded
    ) {}
}
