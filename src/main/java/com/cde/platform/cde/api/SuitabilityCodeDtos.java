package com.cde.platform.cde.api;

import com.cde.platform.cde.domain.ContainerState;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The request and response bodies for a project's suitability codes.
 *
 * <p>The product ships the mechanism and none of the values. Organisations
 * customise these lists, and the code tables printed in the standard itself are
 * copyrighted material that may not be reproduced in a commercial product —
 * so every list is populated by the customer.
 */
public final class SuitabilityCodeDtos {

    private SuitabilityCodeDtos() {
    }

    @Schema(name = "SuitabilityCodeRequest",
            description = "A suitability code the project defines for itself — what information "
                        + "carrying it may be relied on for.")
    public record SuitabilityCodeRequest(

        @Schema(description = "The short code as the organisation writes it.",
                example = "S2", minLength = 1, maxLength = 20,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "A code is required.")
        @Size(max = 20, message = "A code may be at most 20 characters.")
        String code,

        @Schema(description = "What the code means, in the project's own words.",
                example = "Suitable for information", minLength = 1, maxLength = 255,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "A description is required.")
        @Size(max = 255, message = "A description may be at most 255 characters.")
        String description,

        @Schema(description = "Where the code sits in the list the user picks from. Codes with "
                            + "the same order are returned in an unspecified order relative to "
                            + "each other.",
                example = "20", defaultValue = "0")
        Integer displayOrder,

        @Schema(description = """
                The container state this code may be applied in. Omit it and the code applies \
                in any state.

                Setting it is what stops a drawing being marked approved for construction while \
                it is still unverified work in progress — which is most of the reason the list \
                exists.""",
                example = "SHARED", nullable = true)
        ContainerState validInState
    ) {
    }

    @Schema(name = "SuitabilityCodeResponse", description = "A suitability code as stored.")
    public record SuitabilityCodeResponse(

        @Schema(description = "Identifier of the code.", example = "7")
        Long id,

        @Schema(description = "The project the code belongs to. Null for a code defined across "
                            + "the whole tenant.", example = "42", nullable = true)
        Long projectId,

        @Schema(description = "The short code.", example = "S2")
        String code,

        @Schema(description = "What it means.", example = "Suitable for information")
        String description,

        @Schema(description = "Where it sits in the list.", example = "20")
        int displayOrder,

        @Schema(description = "The container state it may be applied in, or null for any.",
                example = "SHARED", nullable = true)
        ContainerState validInState,

        @Schema(description = "Whether the code is still offered. Codes are retired rather than "
                            + "deleted, so revisions that already carry one keep their meaning.",
                example = "true")
        boolean active
    ) {
    }
}
