package com.cde.platform.invitation;

import com.cde.platform.dto.InvitationDtos.InvitationRequest;
import com.cde.platform.dto.InvitationDtos.InvitationResponse;
import com.cde.platform.dto.InvitationDtos.IssuedInvitationResponse;
import com.cde.platform.model.Invitation;
import com.cde.platform.model.User;
import com.cde.platform.openapi.ApiDocumentation;
import com.cde.platform.openapi.StandardErrorResponses;
import com.cde.platform.repository.UserRepository;
import com.cde.platform.security.TenantPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Inviting people into the caller's own organisation.
 *
 * <p>Every endpoint here requires {@code tenant.user:manage}, which only an
 * administrator holds. This is the authority to decide who is inside the
 * isolation boundary — every other permission only decides what somebody
 * already inside may do — so it is deliberately not part of the ISO 19650
 * container vocabulary, where an engineer and a reviewer hold different powers
 * and neither is a superset of the other.
 */
@RestController
@RequestMapping("/api/invitations")
@Tag(name = ApiDocumentation.TAG_TENANT_MEMBERSHIP)
@StandardErrorResponses
public class InvitationController {

    private final InvitationService invitations;
    private final UserRepository users;

    public InvitationController(InvitationService invitations, UserRepository users) {
        this.invitations = invitations;
        this.users = users;
    }

    @Operation(
        operationId = "createInvitation",
        summary = "Invite someone into this organisation",
        description = """
            Issues a single-use invitation to join the caller's own tenant. The tenant is taken \
            from the caller's session and cannot be named in the request — an administrator may \
            only invite people into the organisation they are inside.

            **The token in the reply is shown once and cannot be recovered.** It is stored as a \
            SHA-256 hash, the same treatment an API key gets, because a readable invitation \
            table would be a set of credentials for every pending account. A lost invitation is \
            reissued, not looked up.

            Send it to the invited address over a channel you trust. Redemption requires both \
            the token and that address, so a forwarded invitation does not admit whoever \
            received it.

            Requires the `tenant.user:manage` permission.""")
    @ApiResponse(responseCode = "201", description = "Invitation issued; the token is in the reply.",
        content = @Content(mediaType = "application/json",
                           schema = @Schema(implementation = IssuedInvitationResponse.class)))
    @ApiResponse(responseCode = "422",
        description = "The request failed validation — a missing or malformed email address, "
                    + "or a role that is not one of ADMIN, ENGINEER, REVIEWER or VIEWER.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PostMapping
    @PreAuthorize("hasAuthority('" + TenantPermission.MANAGE_USERS + "')")
    public ResponseEntity<IssuedInvitationResponse> invite(
        @Valid @RequestBody InvitationRequest request,
        @AuthenticationPrincipal UserDetails caller
    ) {
        User issuer = users.findByUsername(caller.getUsername()).orElseThrow(
            () -> new IllegalStateException("Authenticated caller has no user row."));

        var issued = invitations.invite(request.email(), request.role(), issuer.getId());
        Invitation invitation = issued.invitation();

        return ResponseEntity.status(HttpStatus.CREATED).body(new IssuedInvitationResponse(
            invitation.getId(), issued.token(), invitation.getEmail(),
            invitation.getRole().name(), invitation.getExpiresAt()));
    }

    @Operation(
        operationId = "listInvitations",
        summary = "List this organisation's invitations",
        description = """
            Every invitation issued by the caller's tenant, newest first, with its current \
            status: `PENDING`, `ACCEPTED`, `REVOKED` or `EXPIRED`.

            No token is returned. Tokens are shown once, at creation, and are not stored in a \
            recoverable form.

            Requires the `tenant.user:manage` permission.""")
    @ApiResponse(responseCode = "200", description = "The organisation's invitations.")
    @GetMapping
    @PreAuthorize("hasAuthority('" + TenantPermission.MANAGE_USERS + "')")
    public List<InvitationResponse> list() {
        LocalDateTime now = LocalDateTime.now();
        return invitations.issuedByCallerTenant().stream()
            .map(invitation -> toResponse(invitation, now))
            .toList();
    }

    @Operation(
        operationId = "revokeInvitation",
        summary = "Withdraw an unredeemed invitation",
        description = """
            Stops an invitation being redeemed. An invitation that has already been accepted \
            cannot be revoked — the account exists, and removing the invitation would not \
            remove it.

            An invitation belonging to another organisation is reported as absent rather than \
            forbidden, because it genuinely is absent: the row is invisible to this caller under \
            the tenant policy, and this endpoint cannot tell the two apart. That is the intended \
            behaviour, not a limitation — a `403` would confirm the invitation exists.

            Requires the `tenant.user:manage` permission.""")
    @ApiResponse(responseCode = "204", description = "The invitation can no longer be redeemed.")
    @ApiResponse(responseCode = "404",
        description = "No unredeemed invitation with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + TenantPermission.MANAGE_USERS + "')")
    public ResponseEntity<Void> revoke(
        @Parameter(description = "Identifier of the invitation.", example = "17")
        @PathVariable Long id
    ) {
        return invitations.revoke(id)
            ? ResponseEntity.noContent().build()
            : ResponseEntity.notFound().build();
    }

    private InvitationResponse toResponse(Invitation invitation, LocalDateTime now) {
        return new InvitationResponse(
            invitation.getId(), invitation.getEmail(), invitation.getRole().name(),
            invitation.describeStatus(now), invitation.getExpiresAt(),
            invitation.getCreatedAt(), invitation.getAcceptedAt());
    }
}
