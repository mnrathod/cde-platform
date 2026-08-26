package com.cde.platform.dto;

import com.cde.platform.model.User;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Registration and sign-in payloads.
 *
 * <p>Every constraint here is enforced server-side and described identically in
 * the OpenAPI document. A rule that exists in one and not the other is a bug:
 * a client generated from the specification would build requests the server
 * rejects, or fail to warn about ones it will.
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    @Schema(name = "RegisterRequest",
            description = "Details for a new account. A tenant still cannot be named here, "
                        + "because accepting one would let any caller create an account inside "
                        + "another organisation. Without an invitation the account gets a new "
                        + "tenant of its own; with one it joins the tenant that issued it, "
                        + "which is proof rather than an assertion.")
    public record RegisterRequest(

        @Schema(description = "Unique sign-in name within the tenant.",
                example = "j.okafor", minLength = 1, maxLength = 60, requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 60) String username,

        @Schema(description = "Contact address, unique within the tenant. Used for password "
                            + "reset and expiry warnings, so an address the person can actually "
                            + "read matters.",
                example = "j.okafor@example.test", format = "email", maxLength = 254,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Email @Size(max = 254) String email,

        @Schema(description = "Chosen password. Checked against the tenant's policy and against "
                            + "known-breached password sets; long passphrases are preferred to "
                            + "short complex ones. Never returned by any endpoint.",
                example = "correct-horse-battery-staple-42", minLength = 12, maxLength = 128,
                format = "password", requiredMode = Schema.RequiredMode.REQUIRED,
                accessMode = Schema.AccessMode.WRITE_ONLY)
        @NotBlank @Size(min = 12, max = 128) String password,

        @Schema(description = "An invitation issued by an administrator of the organisation to "
                            + "join. Omit it to create a new organisation instead. The invited "
                            + "email address must match the one above, so a forwarded invitation "
                            + "does not admit whoever received it.",
                example = "cdeinv_c3ludGhldGljLXNhbXBsZS10b2tlbi12YWx1ZQ", maxLength = 100,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                accessMode = Schema.AccessMode.WRITE_ONLY,
                nullable = true)
        @Size(max = 100) String invitationToken,

        @Schema(description = "What to call the new organisation. Ignored when an invitation is "
                            + "presented, because that organisation already has a name. Omit it "
                            + "and one is derived from the username — signing up should not "
                            + "require a decision nobody has made yet.",
                example = "Okafor Engineering", maxLength = 200,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true)
        @Size(max = 200) String organisationName

        // There is deliberately no tenant field and no role field. Same
        // argument in both cases: this endpoint requires no credential, so
        // anything it accepts is something a stranger can assert about
        // themselves.
        //
        // The role field existed once, honoured as supplied, so any anonymous
        // caller could register as ADMIN simply by asking. That was already an
        // escalation, and it became a considerably worse one when roles
        // started carrying the container permissions: it would have handed a
        // stranger the authority to publish a contractual record. A role is
        // granted, not chosen — an invited account gets the role its inviter
        // chose, an uninvited one administers only the tenant it just created,
        // and the reply says which role was actually assigned so a client that
        // still sends one is told plainly what it got.
        //
        // The tenant field never existed, and the workaround was worse than
        // the field would have been: every account went into the deployment's
        // default tenant, so anyone who could reach this endpoint could read
        // every project in the deployment. Row-Level Security was enforcing
        // correctly the entire time. It had nothing to separate, because
        // registration had already put everybody on the same side of the
        // boundary. An invitation is how a tenant gets named without a
        // stranger asserting it.
    ) {}

    @Schema(name = "LoginRequest", description = "Credentials for a password sign-in.")
    public record LoginRequest(

        @Schema(description = "Sign-in name.", example = "j.okafor",
                minLength = 1, maxLength = 60, requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String username,

        @Schema(description = "Password for the account.", example = "correct-horse-battery-staple-42",
                format = "password", requiredMode = Schema.RequiredMode.REQUIRED,
                accessMode = Schema.AccessMode.WRITE_ONLY)
        @NotBlank String password
    ) {}

    @Schema(name = "AuthResponse",
            description = "A signed session. The token carries the subject and the tenant; the "
                        + "tenant claim is authoritative and cannot be overridden by any request "
                        + "parameter.")
    public record AuthResponse(

        @Schema(description = "Signed JSON Web Token, sent on subsequent requests as "
                            + "`Authorization: Bearer <token>`.",
                example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJqLm9rYWZvciJ9.c3ludGhldGljLXNpZ25hdHVyZQ",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String token,

        @Schema(description = "Sign-in name the token authenticates.", example = "j.okafor",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String username,

        @Schema(description = "Role the account holds. Client-side use is presentation only — "
                            + "every permission is re-checked server-side on every request.",
                example = "ENGINEER", requiredMode = Schema.RequiredMode.REQUIRED)
        String role
    ) {}
}
