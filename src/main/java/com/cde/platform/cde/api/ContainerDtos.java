package com.cde.platform.cde.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * The request and response bodies for information containers.
 *
 * <p>Every constraint here appears twice on purpose — once as a Bean Validation
 * annotation, which is what actually rejects the request, and once in the
 * schema, which is what the published specification and the generated client
 * are built from. They are checked against each other in CI, because a limit
 * that exists only in the code is a limit callers discover by hitting it.
 */
public final class ContainerDtos {

    private ContainerDtos() {
    }

    @Schema(name = "ContainerRequest",
            description = "The identity of a piece of project information, independent of any "
                        + "revision of its content.")
    public record ContainerRequest(

        @Schema(description = "The assembled, delimited identifier people read and search on. "
                            + "The field pattern and delimiter are the project's own convention, "
                            + "so this is validated for length and uniqueness here rather than "
                            + "against a shipped format.",
                example = "PRJ-XYZ-ZZ-00-DR-A-0001",
                minLength = 1, maxLength = 255, requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "A container reference is required.")
        @Size(max = 255, message = "A container reference may be at most 255 characters.")
        String containerReference,

        @Schema(description = "The individual naming fields, keyed by the name the project's "
                            + "convention gives each one. Free-form because the field set differs "
                            + "between organisations; omit it and the reference stands alone.",
                example = """
                    {"project":"PRJ","originator":"XYZ","volume":"ZZ","level":"00",\
                    "type":"DR","role":"A","number":"0001"}""")
        Map<String, String> namingFields
    ) {
    }

    @Schema(name = "ContainerResponse",
            description = """
                A container as stored.

                There is no state on a container, and that is not an omission: state belongs to \
                its revisions, and a container in normal use has several at once — a published \
                revision that is the current contractual record, and a work-in-progress revision \
                that will supersede it. Reporting a single state here would have to pick one of \
                them and would misrepresent the other.""")
    public record ContainerResponse(

        @Schema(description = "Identifier of the container.", example = "1024")
        Long id,

        @Schema(description = "The project the container belongs to.", example = "42")
        Long projectId,

        @Schema(description = "The assembled, delimited identifier.",
                example = "PRJ-XYZ-ZZ-00-DR-A-0001")
        String containerReference,

        @Schema(description = "The individual naming fields as supplied.",
                example = """
                    {"project":"PRJ","originator":"XYZ","volume":"ZZ","level":"00",\
                    "type":"DR","role":"A","number":"0001"}""")
        Map<String, String> namingFields,

        @Schema(description = "Username of whoever created the container.", example = "a.surveyor")
        String createdBy,

        @Schema(description = "When the container was created, in the server's time zone.",
                format = "date-time", example = "2026-03-04T09:15:00")
        LocalDateTime createdAt
    ) {
    }
}
