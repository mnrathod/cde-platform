package com.cde.platform.dto;

import com.cde.platform.model.User;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * Payloads for inviting people into a tenant.
 *
 * <p>Every constraint here is enforced server-side and described identically in
 * the OpenAPI document, for the same reason as the auth payloads: a client
 * generated from the specification must not be able to build a request the
 * server rejects.
 */
public final class InvitationDtos {

    private InvitationDtos() {
    }

    @Schema(name = "InvitationRequest",
            description = "An offer to join the caller's own organisation. The tenant is taken "
                        + "from the caller's session and cannot be named here — an administrator "
                        + "may only invite people into the organisation they are inside.")
    public record InvitationRequest(

        @Schema(description = "Address to invite. Registration must present this same address, "
                            + "so a forwarded invitation does not admit whoever received it.",
                example = "n.dubois@example.test", format = "email", maxLength = 254,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Email @Size(max = 254) String email,

        @Schema(description = "Role the invitee receives on joining. Chosen by the inviting "
                            + "administrator, never by the person redeeming the invitation.",
                example = "ENGINEER", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull User.Role role
    ) {}

    @Schema(name = "IssuedInvitationResponse",
            description = "A newly created invitation. This is the only time the token is ever "
                        + "returned: it is stored as a SHA-256 hash and cannot be recovered, so "
                        + "a lost one is reissued rather than looked up.")
    public record IssuedInvitationResponse(

        @Schema(description = "Identifier, for revoking it later.", example = "17",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,

        @Schema(description = "The invitation token. Send it to the invited address over a "
                            + "channel you trust — anyone holding it and that address can join "
                            + "the organisation.",
                example = "cdeinv_c3ludGhldGljLXNhbXBsZS10b2tlbi12YWx1ZQ",
                requiredMode = Schema.RequiredMode.REQUIRED,
                accessMode = Schema.AccessMode.READ_ONLY)
        String token,

        @Schema(description = "Address the invitation admits.", example = "n.dubois@example.test",
                format = "email", requiredMode = Schema.RequiredMode.REQUIRED)
        String email,

        @Schema(description = "Role the invitee will receive.", example = "ENGINEER",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String role,

        @Schema(description = "When it stops being redeemable.",
                example = "2026-09-02T09:15:00", requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDateTime expiresAt
    ) {}

    @Schema(name = "InvitationResponse",
            description = "An invitation as it appears in a listing. It carries no token — that "
                        + "was shown once, at creation.")
    public record InvitationResponse(

        @Schema(description = "Identifier.", example = "17",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,

        @Schema(description = "Address the invitation admits.", example = "n.dubois@example.test",
                format = "email", requiredMode = Schema.RequiredMode.REQUIRED)
        String email,

        @Schema(description = "Role the invitee receives.", example = "ENGINEER",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String role,

        @Schema(description = "Where it stands.",
                allowableValues = {"PENDING", "ACCEPTED", "REVOKED", "EXPIRED"},
                example = "PENDING", requiredMode = Schema.RequiredMode.REQUIRED)
        String status,

        @Schema(description = "When it stops being redeemable.",
                example = "2026-09-02T09:15:00", requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDateTime expiresAt,

        @Schema(description = "When it was issued.", example = "2026-08-26T09:15:00",
                requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDateTime createdAt,

        @Schema(description = "When it was redeemed, or null if it has not been.",
                example = "2026-08-27T11:02:00", nullable = true,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        LocalDateTime acceptedAt
    ) {}
}
