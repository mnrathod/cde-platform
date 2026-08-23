package com.cde.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Client-side error reporting and service status. */
public final class DiagnosticsDtos {

    private DiagnosticsDtos() {
    }

    @Schema(name = "ClientErrorReport",
            description = """
                An error the browser hit, forwarded so it can be correlated with the server-side \
                logs for the same session.

                Nothing here is trusted. Every member is length-bounded and stripped of control \
                characters before it reaches a log, because a caller that could embed a line \
                break could forge log entries.""")
    public record ClientErrorReport(

        @Schema(description = "How serious the client considered it.", example = "error",
                allowableValues = {"error", "warning", "info"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 16) String level,

        @Schema(description = "What went wrong.", example = "Cannot read properties of undefined",
                maxLength = 2000, requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 2000) String message,

        @Schema(description = "What kind of failure it was, as the client classified it.",
                example = "http-error", maxLength = 60)
        @Size(max = 60) String type,

        @Schema(description = "Route the browser was on. A path, not a full URL — a full URL can "
                            + "carry query parameters that hold personal data.",
                example = "/projects/42/documents/1180", maxLength = 512)
        @Size(max = 512) String url,

        @Schema(description = "When the client recorded it, UTC. The server records its own "
                            + "arrival time regardless, so a client with a wrong clock cannot "
                            + "misplace the entry.",
                example = "2026-02-21T11:58:03.221Z", format = "date-time", maxLength = 40)
        @Size(max = 40) String timestamp
    ) {}

    @Schema(name = "ConverterStatusResponse",
            description = "Whether the document conversion service is reachable. The viewer asks "
                        + "before offering conversion-dependent actions, so it can say why "
                        + "something is unavailable rather than failing when it is tried.")
    public record ConverterStatusResponse(

        @Schema(description = "Whether the conversion service answered.", example = "true",
                requiredMode = Schema.RequiredMode.REQUIRED)
        boolean running
    ) {}
}
