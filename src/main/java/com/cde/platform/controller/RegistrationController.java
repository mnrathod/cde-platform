package com.cde.platform.controller;

import com.cde.platform.audit.AuditAction;
import com.cde.platform.audit.AuditOutcome;
import com.cde.platform.audit.AuditRequest;
import com.cde.platform.audit.AuditableChange;
import com.cde.platform.audit.RequestAuditor;
import com.cde.platform.dto.AuthDtos.*;
import com.cde.platform.exception.ApiProblem;
import com.cde.platform.invitation.RegistrationService;
import com.cde.platform.openapi.ApiDocumentation;
import com.cde.platform.security.JwtTokenService;
import com.cde.platform.security.SessionCookie;
import com.cde.platform.tenancy.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Creating an account: into a new tenant, or into the one that invited it.
 *
 * <p>Separate from {@link AuthController} because it is a different job with
 * a different threat model. Signing in proves you already have an account;
 * this decides whether you get one, which tenant it lands in, and what
 * authority it carries — and every one of those answers has to be safe
 * against a caller who holds no credential at all. It also kept the two of
 * them in one file well past the §3.3 limit.
 *
 * <p>The path is unchanged. Which class serves `/api/auth/register` is not
 * something a caller can observe, and moving an endpoint between classes must
 * never move it between URLs (§3.4).
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = ApiDocumentation.TAG_AUTHENTICATION)
@SecurityRequirements  // this endpoint is how a caller gets a credential
public class RegistrationController {

    private final RegistrationService registrationService;
    private final JwtTokenService jwtTokenService;
    private final RequestAuditor auditor;
    private final SessionCookie sessionCookie;

    public RegistrationController(RegistrationService registrationService,
                                  JwtTokenService jwtTokenService,
                                  RequestAuditor auditor,
                                  SessionCookie sessionCookie) {
        this.registrationService = registrationService;
        this.jwtTokenService = jwtTokenService;
        this.auditor = auditor;
        this.sessionCookie = sessionCookie;
    }


    /**
     * Registers into a new tenant, or into the one that invited the caller.
     *
     * <p>Self-service registration still cannot choose its own tenant —
     * accepting a tenant identifier from the request body would let any caller
     * create an account inside someone else's organisation. It used to resolve
     * that by putting everyone into the deployment's default tenant, which
     * meant anyone who could reach this endpoint could read every project in
     * the deployment. An invitation is the third option: proof issued from
     * inside the tenant, rather than an assertion made about it.
     */
    @Operation(
        operationId = "register",
        summary = "Create an account, in a new organisation or an inviting one",
        description = """
            Self-service registration, in one of two shapes.

            **With no invitation** the account gets a **new tenant of its own**, and the \
            registrant administers it — somebody has to be able to invite the second person, and \
            in a one-member organisation there is nobody else to grant that. The authority \
            covers nothing but what the caller is about to create.

            **With an invitation** the account joins the tenant that issued it, with the role \
            the inviting administrator chose. The invited email address must match the one in \
            this request, so a forwarded invitation does not admit whoever received it.

            A tenant still cannot be named directly, and neither can a role: this endpoint \
            requires no credential, so anything it accepts is something a stranger can assert \
            about themselves. The reply states the role actually assigned.

            A deployment may set `cde.tenancy.self-registration` to `INVITATION_ONLY` or \
            `DISABLED`, in which case the uninvited cases above return `403`.

            No permission is required — this endpoint is how a caller gets one.""")
    @ApiResponse(responseCode = "201",
        description = "The account was created, and the reply carries a token for it — no "
                    + "separate sign-in is needed.",
        content = @Content(mediaType = "application/json",
                           schema = @Schema(implementation = AuthResponse.class)))
    @ApiResponse(responseCode = "400",
        description = "The request body could not be read.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "403",
        description = "Self-service registration is closed on this deployment, or it requires "
                    + "an invitation and none was presented.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "409",
        description = "The username or the email address is already in use. The reply does not "
                    + "say which, so this cannot be used to test whether a given person has an "
                    + "account here.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "422",
        description = "The details failed validation — most often a password shorter than the "
                    + "tenant's minimum, or one found in a known-breached set. Also returned "
                    + "when an invitation is unknown, expired, revoked, already redeemed, or "
                    + "issued to a different address; those are one answer, because "
                    + "distinguishing them tells a caller whether a guessed token is real.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "429",
        description = "Too many registrations from this source. `Retry-After` gives the seconds "
                    + "to wait.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "500",
        description = "Registration failed for a reason the caller cannot act on. Quote the "
                    + "`traceId` when reporting it.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest req,
                                      HttpServletRequest httpRequest) {
        var outcome = registrationService.register(
            req.username(), req.email(), req.password(),
            req.invitationToken(), req.organisationName());

        return switch (outcome) {
            case RegistrationService.Outcome.Registered registered -> {
                // Recorded against the tenant the account just joined or
                // founded, which RegistrationService leaves bound. The role is
                // recorded because "who granted this account ADMIN" is the
                // question asked afterwards, and the answer for a founder is
                // "the act of founding".
                TenantContext.runAsTenant(registered.tenant().getId(), () ->
                    auditor.record(
                        AuditRequest.by(registered.user().getId(),
                                        registered.user().getUsername())
                            .did(registered.joinedByInvitation()
                                 ? AuditAction.INVITATION_REDEEMED
                                 : AuditAction.REGISTRATION)
                            .outcome(AuditOutcome.SUCCESS)
                            .to("User", registered.user().getId())
                            .changing(AuditableChange
                                .of("role", registered.user().getRole())
                                .and("organisation", registered.tenant().getSlug())),
                        httpRequest));

                String token = jwtTokenService.generateToken(
                    registered.user().getUsername(), registered.tenant().getId());
                yield ResponseEntity.status(HttpStatus.CREATED)
                    .header(HttpHeaders.SET_COOKIE, sessionCookie.issueFor(token).toString())
                    .body(new AuthResponse(
                        token, registered.user().getUsername(),
                        registered.user().getRole().name()));
            }

            // One message covering username and email alike. Saying which one
            // clashed turns registration into a test for whether a given
            // person has an account here.
            case RegistrationService.Outcome.IdentityTaken ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(ApiProblem.of(
                    HttpStatus.CONFLICT, "identity-taken", "Already registered",
                    "That username or email address is already in use. Sign in instead, "
                        + "or choose another username.", httpRequest));

            case RegistrationService.Outcome.RegistrationClosed ignored ->
                ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiProblem.of(
                    HttpStatus.FORBIDDEN, "registration-closed", "Registration closed",
                    "This deployment does not accept self-service registration. "
                        + "Ask an administrator to create your account.", httpRequest));

            case RegistrationService.Outcome.InvitationRequired ignored ->
                ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiProblem.of(
                    HttpStatus.FORBIDDEN, "invitation-required", "Invitation required",
                    "This deployment only accepts registrations with an invitation. "
                        + "Ask an administrator of your organisation to send you one.",
                    httpRequest));

            // Deliberately one answer for unknown, expired, revoked, already
            // used, and issued to another address. Distinguishing them tells
            // someone holding a guessed token whether they guessed a real one.
            case RegistrationService.Outcome.InvitationNotUsable ignored ->
                ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(ApiProblem.of(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invitation-not-usable",
                    "Invitation cannot be used",
                    "That invitation is not valid for this email address, or it has expired "
                        + "or already been used. Ask for a new one.", httpRequest));
        };
    }

}
