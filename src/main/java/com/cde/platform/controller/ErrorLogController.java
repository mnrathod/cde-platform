package com.cde.platform.controller;

import com.cde.platform.dto.DiagnosticsDtos.ClientErrorReport;
import com.cde.platform.openapi.ApiDocumentation;
import com.cde.platform.openapi.StandardErrorResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Receives error events from the browser so a failure a user saw can be
 * correlated with the server-side logs for the same session.
 */
@RestController
@RequestMapping("/api/logs")
@Tag(name = ApiDocumentation.TAG_DIAGNOSTICS)
@StandardErrorResponses
public class ErrorLogController {

    private static final Logger log = LoggerFactory.getLogger(ErrorLogController.class);

    /**
     * Anything that would let a caller start a new line, and so forge a log
     * entry of their own.
     */
    private static final String LINE_BREAKING = "[\\r\\n\\u0085\\u2028\\u2029]";

    @Operation(
        operationId = "reportClientError",
        summary = "Report an error the browser hit",
        description = """
            Forwarded to the server log so a failure a user saw can be lined up with what the \
            server was doing at the time. The request's own trace id ties the two together.

            Nothing in the body is trusted. Every member is length-bounded and stripped of line \
            breaks before it reaches a log — a caller able to embed a newline could otherwise \
            write log entries of their own choosing.

            Returns `202`: the report is accepted for logging, and the caller is not made to wait \
            for that to happen or told whether it did. A client should never block its own error \
            handling on this succeeding.

            No permission is required — an error worth reporting often happens when the session \
            has already failed.""")
    @ApiResponse(responseCode = "202", description = "The report was accepted.")
    @ApiResponse(responseCode = "422",
        description = "The report failed validation — no level or message, or a member over its "
                    + "length limit.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PostMapping("/errors")
    public ResponseEntity<Void> logError(@Valid @RequestBody ClientErrorReport event) {
        // Structured so a log aggregator can index the members rather than
        // parsing them back out of a sentence.
        log.atLevel(switch (safe(event.level())) {
                case "error"   -> org.slf4j.event.Level.ERROR;
                case "warning" -> org.slf4j.event.Level.WARN;
                default        -> org.slf4j.event.Level.INFO;
            })
            .addKeyValue("source", "browser")
            .addKeyValue("clientType", safe(event.type()))
            .addKeyValue("clientRoute", safe(event.url()))
            .addKeyValue("clientTimestamp", safe(event.timestamp()))
            .log("Client error: {}", safe(event.message()));

        return ResponseEntity.accepted().build();
    }

    /** Strips anything that could start a new log line. */
    private String safe(String value) {
        return value == null ? "" : value.replaceAll(LINE_BREAKING, " ");
    }
}
