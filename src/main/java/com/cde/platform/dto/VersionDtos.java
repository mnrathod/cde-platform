package com.cde.platform.dto;

import com.cde.platform.model.DocumentVersion;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/** A document's revision history. */
public final class VersionDtos {

    private VersionDtos() {
    }

    @Schema(name = "DocumentVersionResponse",
            description = "One entry in a document's history. Every operation that rewrites a "
                        + "document commits one of these, and every earlier entry stays "
                        + "downloadable.")
    public record DocumentVersionResponse(

        @Schema(description = "Version number, counting from 1 for the original upload.",
                example = "3", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        Integer version,

        @Schema(description = "What produced this version.", example = "REDACT",
                requiredMode = Schema.RequiredMode.REQUIRED)
        DocumentVersion.DocumentOperation operation,

        @Schema(description = "What changed, in one line, written for the history panel.",
                example = "Redacted 6 regions across 2 pages.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String summary,

        @Schema(description = "File name this version was written under.",
                example = "RVD-XX-02-DR-A-1200.pdf")
        String fileName,

        @Schema(description = "Size of this version in bytes.", example = "4721664", minimum = "0")
        Long fileSize,

        @Schema(description = "SHA-256 of this version's bytes, so a downloaded copy can be "
                            + "checked against what the server holds.",
                example = "9f2c4b7e1a8d3f60c5b2e9a47d18f3c06b5e2a91d7f4c308b6a1e5d9c2f70348",
                pattern = "^[0-9a-f]{64}$")
        String contentHash,

        @Schema(description = "Sign-in name of the account that committed it.", example = "j.okafor",
                accessMode = Schema.AccessMode.READ_ONLY)
        String createdBy,

        @Schema(description = "When this version was committed, UTC.", example = "2026-02-21T10:33:09",
                format = "date-time", accessMode = Schema.AccessMode.READ_ONLY,
                requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDateTime createdAt,

        @Schema(description = "Whether this is the version the document currently serves.",
                example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean current
    ) {
        public static DocumentVersionResponse from(DocumentVersion version, Integer headVersion) {
            return new DocumentVersionResponse(
                version.getVersionNumber(),
                version.getOperation(),
                version.getSummary(),
                version.getFileName(),
                version.getFileSize(),
                version.getContentHash(),
                version.getCreatedBy() != null ? version.getCreatedBy().getUsername() : null,
                version.getCreatedAt(),
                version.getVersionNumber().equals(headVersion));
        }
    }
}
