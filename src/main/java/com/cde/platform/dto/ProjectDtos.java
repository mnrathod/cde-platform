package com.cde.platform.dto;

import com.cde.platform.model.Project;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/** Project payloads. A project groups documents and carries their phase and ownership. */
public final class ProjectDtos {

    private ProjectDtos() {
    }

    @Schema(name = "ProjectRequest",
            description = "Details of a project to create or replace. Tenant and owner are taken "
                        + "from the authenticated principal and are not settable here.")
    public record ProjectRequest(

        @Schema(description = "Name shown wherever the project is listed.",
                example = "Riverside Depot — Stage 2", minLength = 1, maxLength = 200,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 200) String name,

        @Schema(description = "Free text describing the scope of works.",
                example = "Platform extension and signalling upgrade.", maxLength = 2000,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Size(max = 2000) String description,

        @Schema(description = "Where the works are. Free text rather than coordinates, because "
                            + "this is for people reading a list, not for mapping.",
                example = "Riverside, South Yard", maxLength = 200,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Size(max = 200) String location,

        @Schema(description = "Delivery phase. Defaults to `CONCEPT` on creation. On replacement, "
                            + "omitting it keeps the current phase rather than clearing it — a "
                            + "project moves through phases in order, and an omission should not "
                            + "walk it backwards.",
                example = "DESIGN", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Project.ProjectPhase phase
    ) {}

    @Schema(name = "ProjectResponse", description = "A project as it currently stands.")
    public record ProjectResponse(

        @Schema(description = "Identifier of the project.", example = "42",
                accessMode = Schema.AccessMode.READ_ONLY, requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,

        @Schema(description = "Name shown wherever the project is listed.",
                example = "Riverside Depot — Stage 2", requiredMode = Schema.RequiredMode.REQUIRED)
        String name,

        @Schema(description = "Free text describing the scope of works.",
                example = "Platform extension and signalling upgrade.")
        String description,

        @Schema(description = "Where the works are.", example = "Riverside, South Yard")
        String location,

        @Schema(description = "Delivery phase.", example = "DESIGN",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Project.ProjectPhase phase,

        @Schema(description = "Sign-in name of the account that owns the project.",
                example = "j.okafor", accessMode = Schema.AccessMode.READ_ONLY)
        String ownerUsername,

        @Schema(description = "When the project was created, UTC.",
                example = "2026-01-08T11:20:04", format = "date-time",
                accessMode = Schema.AccessMode.READ_ONLY, requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDateTime createdAt,

        @Schema(description = "When any detail of the project last changed, UTC.",
                example = "2026-02-14T16:05:51", format = "date-time",
                accessMode = Schema.AccessMode.READ_ONLY)
        LocalDateTime updatedAt,

        @Schema(description = "How many documents the project holds. Present so a list can show "
                            + "it without a follow-up request per row.",
                example = "37", minimum = "0",
                accessMode = Schema.AccessMode.READ_ONLY, requiredMode = Schema.RequiredMode.REQUIRED)
        int documentCount
    ) {}
}
