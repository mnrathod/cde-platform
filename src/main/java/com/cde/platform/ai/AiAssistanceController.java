package com.cde.platform.ai;

import com.cde.platform.ai.AiPayloadSanitiser.ComparisonFacts;
import com.cde.platform.exception.ApiProblem;
import com.cde.platform.model.User;
import com.cde.platform.openapi.ApiDocumentation;
import com.cde.platform.openapi.StandardErrorResponses;
import com.cde.platform.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Assisted summaries of things this platform already knows.
 *
 * <p>This replaced a pair of endpoints that forwarded a caller-supplied body
 * verbatim to a third-party model provider. That was not a filter configured
 * badly; it was a shape in which no filter is possible. The data-handling rules
 * require allow-list field selection on every outbound payload, and you cannot
 * allow-list the fields of a payload whose structure the caller defines.
 *
 * <p>So the contract changed. A caller no longer sends a prompt, a model, or a
 * token ceiling — it names what it wants summarised, and the prompt is built on
 * this side from fields {@link AiPayloadSanitiser} selected. The client no
 * longer decides what this deployment spends, or what it spends it on.
 */
@RestController
@RequestMapping("/api/ai")
@Tag(name = ApiDocumentation.TAG_ASSISTANCE)
@StandardErrorResponses
public class AiAssistanceController {

    private final AiAssistanceService assistance;
    private final UserRepository users;

    public AiAssistanceController(AiAssistanceService assistance, UserRepository users) {
        this.assistance = assistance;
        this.users = users;
    }

    @Operation(
        operationId = "getAssistanceAvailability",
        summary = "Whether this deployment offers assisted summaries",
        description = """
            Reports whether a model provider is configured and permitted on this deployment, so \
            a client can hide the feature rather than offer one that will be refused.

            It is `false` on any government or Defence deployment regardless of configuration: \
            those tiers make no outbound calls, and the check is not something a tenant \
            administrator can override.

            Requires authentication.""")
    @ApiResponse(responseCode = "200", description = "Whether the feature can be used.",
        content = @Content(mediaType = "application/json",
                           schema = @Schema(implementation = AssistanceAvailability.class)))
    @GetMapping("/availability")
    public AssistanceAvailability availability() {
        return new AssistanceAvailability(assistance.isAvailable());
    }

    @Operation(
        operationId = "summariseComparison",
        summary = "Produce a review report for a comparison",
        description = """
            Turns the differences between two revisions into a structured review report.

            **The request carries facts, not a prompt.** The prompt, the model and the token \
            ceiling are all decided on the server. This matters for more than tidiness: the \
            outbound payload is built from an allow-list of named fields, so a field added to a \
            comparison later does not travel to a third party until somebody decides it should.

            Before anything is sent, the selected fields are scanned. Personal identifiers — \
            email addresses, telephone numbers, addresses in URLs — are replaced with stable \
            placeholders and put back locally on the way home, so the provider never sees them. \
            Content carrying a **classification marking is refused outright** rather than \
            redacted: there is no redaction that makes such material safe to send, and a \
            partial send is still a send.

            Every call is recorded in the audit trail — who, when, which model and provider, and \
            whether redaction fired. The prompt and the reply are never recorded.

            Requires authentication.""")
    @ApiResponse(responseCode = "200", description = "The report.",
        content = @Content(mediaType = "application/json",
                           schema = @Schema(implementation = ComparisonReportResponse.class)))
    @ApiResponse(responseCode = "422",
        description = "The content carries a classification marking and will not be sent to a "
                    + "third party. The reply names which field, never its contents.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "503",
        description = "No provider is configured, or this deployment tier makes no outbound "
                    + "calls, or the provider could not be reached.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PostMapping("/comparison-report")
    public ResponseEntity<?> summariseComparison(
        @Valid @RequestBody ComparisonReportRequest request,
        @AuthenticationPrincipal UserDetails caller,
        HttpServletRequest httpRequest
    ) {
        User actor = users.findByUsername(caller.getUsername()).orElseThrow();

        var outcome = assistance.summariseComparison(
            new ComparisonFacts(request.firstDocumentName(), request.secondDocumentName(),
                                request.firstRevision(), request.secondRevision(),
                                request.documentKind(), request.changes()),
            actor.getId(), actor.getUsername(), httpRequest);

        if (!outcome.available()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiProblem.of(
                HttpStatus.SERVICE_UNAVAILABLE, "assistance-unavailable",
                "Assisted summaries are unavailable",
                "This deployment does not offer assisted summaries, or the provider could not "
                    + "be reached. The comparison itself is unaffected.", httpRequest));
        }
        if (outcome.wasRefused()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(ApiProblem.of(
                HttpStatus.UNPROCESSABLE_ENTITY, "classified-content-refused",
                "Content cannot be sent to a model provider",
                outcome.refusalDetail(), httpRequest));
        }
        return ResponseEntity.ok(new ComparisonReportResponse(outcome.report()));
    }

    @Schema(name = "ComparisonReportRequest",
        description = "The facts to summarise. Deliberately not a prompt: the prompt is built "
                    + "on the server from these fields, which is what makes an allow-list "
                    + "possible.")
    public record ComparisonReportRequest(

        @Schema(description = "Name of the earlier revision's document.",
                example = "GA Plan — Level 02", maxLength = 400,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 400) String firstDocumentName,

        @Schema(description = "Name of the later revision's document.",
                example = "GA Plan — Level 02", maxLength = 400,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 400) String secondDocumentName,

        @Schema(description = "Revision identifier of the earlier document.", example = "P01",
                maxLength = 400, requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 400) String firstRevision,

        @Schema(description = "Revision identifier of the later document.", example = "P02",
                maxLength = 400, requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 400) String secondRevision,

        @Schema(description = "What kind of document was compared.", example = "drawing",
                maxLength = 64, requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 64) String documentKind,

        @Schema(description = "The changes detected, one per entry. At most 40 are sent; the "
                            + "rest are summarised by count.",
                example = "[\"Door schedule updated on grid line C\"]",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @Size(max = 500) List<@Size(max = 400) String> changes
    ) {}

    @Schema(name = "ComparisonReportResponse", description = "The assisted review report.")
    public record ComparisonReportResponse(

        @Schema(description = "The report, in five sections. Any placeholder the provider saw "
                            + "has been replaced with the real value before this reply was "
                            + "assembled.",
                example = "Revision summary\\nThe door schedule was revised...",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String report
    ) {}

    @Schema(name = "AssistanceAvailability",
        description = "Whether assisted summaries can be used on this deployment.")
    public record AssistanceAvailability(

        @Schema(description = "False on any deployment that makes no outbound calls, and on any "
                            + "with no provider configured.",
                example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean available
    ) {}
}
