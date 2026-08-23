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
            description = "Details for a new account. The account is created in the default "
                        + "tenant; a tenant cannot be chosen here, because accepting one would "
                        + "let any caller create an account inside another organisation.")
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
        @NotBlank @Size(min = 12, max = 128) String password
        // There is deliberately no role field.
        //
        // There was one, honoured as supplied, on an endpoint that requires no
        // credential — so any anonymous caller could register as ADMIN simply
        // by asking. That was already an escalation, and it became a
        // considerably worse one when roles started carrying the container
        // permissions: it would have handed a stranger the authority to
        // publish a contractual record.
        //
        // A role is granted, not chosen. Every account starts as ENGINEER, and
        // the reply says which role was actually assigned, so a client that
        // still sends one is told plainly what it got rather than being left
        // to assume.
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
