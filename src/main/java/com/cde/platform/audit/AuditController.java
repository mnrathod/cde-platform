package com.cde.platform.audit;

import com.cde.platform.audit.AuditDtos.AuditChainVerificationResponse;
import com.cde.platform.audit.AuditDtos.AuditEventResponse;
import com.cde.platform.dto.PageResponse;
import com.cde.platform.openapi.ApiDocumentation;
import com.cde.platform.openapi.StandardErrorResponses;
import com.cde.platform.security.TenantPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Reading this organisation's audit trail.
 *
 * <p>Read-only, and that is structural rather than a decision taken here: the
 * application's database role holds {@code INSERT} and {@code SELECT} on the
 * table and nothing else, so no endpoint could offer an edit or a delete even
 * if one were written.
 *
 * <p>Scoped to the caller's own tenant by Row-Level Security, like everything
 * else. There is no parameter for whose trail to read, because there is no
 * answer to that question other than "your own".
 */
@RestController
@RequestMapping("/api/audit-events")
@Tag(name = ApiDocumentation.TAG_AUDIT)
@StandardErrorResponses
@Validated
public class AuditController {

    /**
     * Bounded so that one request cannot pull an organisation's entire history
     * into memory. Export belongs on a job (§7.1), not on a large page.
     */
    private static final int MAX_PAGE_SIZE = 200;

    private final AuditEventRepository events;
    private final AuditTrailService auditTrail;

    public AuditController(AuditEventRepository events, AuditTrailService auditTrail) {
        this.events = events;
        this.auditTrail = auditTrail;
    }

    @Operation(
        operationId = "listAuditEvents",
        summary = "Read this organisation's audit trail",
        description = """
            Returns this organisation's security-relevant events, newest first. The trail is \
            append-only and hash-chained: each record carries the SHA-256 of the record before \
            it, so an alteration anywhere breaks every hash after it, and the sequence numbers \
            are contiguous so a removal leaves a gap.

            Both hashes are returned so that an organisation streaming this to its own SIEM can \
            verify the chain itself rather than being told it verified.

            Records never carry a credential, a request body, or raw personal data — what \
            changed is summarised by field name, with values that are identifiers or booleans.

            Requires the `tenant.audit:read` permission.""")
    @ApiResponse(responseCode = "200", description = "A page of records, newest first.",
        content = @Content(mediaType = "application/json",
                           schema = @Schema(implementation = PageResponse.class)))
    @GetMapping
    @PreAuthorize("hasAuthority('" + TenantPermission.READ_AUDIT_TRAIL + "')")
    public PageResponse<AuditEventResponse> list(
        @Parameter(description = "Zero-based page index.", example = "0")
        @RequestParam(defaultValue = "0") @Min(0) int page,

        @Parameter(description = "Records per page, at most " + MAX_PAGE_SIZE + ".",
                   example = "50")
        @RequestParam(defaultValue = "50") @Min(1) @Max(MAX_PAGE_SIZE) int size,

        @Parameter(description = "Return only records for this action.",
                   example = "AUTHORISATION_DENIED")
        @RequestParam(required = false) AuditAction action
    ) {
        var pageable = PageRequest.of(page, size);
        var found = action == null
            ? events.findAllByOrderBySequenceNumberDesc(pageable)
            : events.findByActionOrderBySequenceNumberDesc(action, pageable);
        return PageResponse.from(found, AuditEventResponse::from);
    }

    @Operation(
        operationId = "verifyAuditChain",
        summary = "Recompute every hash in this organisation's chain",
        description = """
            Reads the whole trail and recomputes each record's hash from its contents and the \
            hash of the record before it. Reports the first record that does not verify, or \
            that the chain is intact.

            This detects tampering; it does not prevent it. Prevention needs storage the \
            application cannot reach — the trail is shaped to be exported to write-once storage \
            for that.

            Reads every record, so it is an operator action rather than something to poll. \
            Requires the `tenant.audit:read` permission.""")
    @ApiResponse(responseCode = "200",
        description = "The verification result. A 200 with `intact: false` is the answer to a "
                    + "successful verification that found a break — not an error.",
        content = @Content(mediaType = "application/json",
                           schema = @Schema(implementation = AuditChainVerificationResponse.class)))
    @GetMapping("/verification")
    @PreAuthorize("hasAuthority('" + TenantPermission.READ_AUDIT_TRAIL + "')")
    public ResponseEntity<AuditChainVerificationResponse> verify() {
        return ResponseEntity.ok(
            AuditChainVerificationResponse.from(auditTrail.verifyChain()));
    }
}
