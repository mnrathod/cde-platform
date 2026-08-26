package com.cde.platform.controller;

import com.cde.platform.dto.AuthDtos.*;
import com.cde.platform.exception.ApiProblem;
import com.cde.platform.openapi.ApiDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.cde.platform.invitation.RegistrationService;
import com.cde.platform.model.User;
import com.cde.platform.repository.TenantRepository;
import com.cde.platform.repository.UserRepository;
import com.cde.platform.security.JwtTokenService;
import com.cde.platform.tenancy.LoginTenantResolver;
import com.cde.platform.tenancy.TenancyProperties;
import com.cde.platform.tenancy.TenantContext;
import com.cde.platform.tenancy.TenantContextBinder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.authentication.*;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
@Tag(name = ApiDocumentation.TAG_AUTHENTICATION)
@SecurityRequirements  // these two endpoints are how a caller gets a credential
public class AuthController {

    /**
     * One message for every failure mode. Distinguishing "no such user" from
     * "wrong password" — or from "that user is in a tenant you cannot reach" —
     * turns the login endpoint into an account-enumeration oracle.
     */
    private static final String GENERIC_LOGIN_FAILURE = "Invalid credentials";

    private final UserRepository userRepo;
    private final JwtTokenService jwtTokenService;
    private final AuthenticationManager authManager;
    private final LoginTenantResolver loginTenantResolver;
    private final RegistrationService registrationService;

    public AuthController(UserRepository userRepo,
                          JwtTokenService jwtTokenService,
                          AuthenticationManager authManager,
                          LoginTenantResolver loginTenantResolver,
                          RegistrationService registrationService) {
        this.userRepo = userRepo;
        this.jwtTokenService = jwtTokenService;
        this.authManager = authManager;
        this.loginTenantResolver = loginTenantResolver;
        this.registrationService = registrationService;
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
                String token = jwtTokenService.generateToken(
                    registered.user().getUsername(), registered.tenant().getId());
                yield ResponseEntity.status(HttpStatus.CREATED).body(new AuthResponse(
                    token, registered.user().getUsername(), registered.user().getRole().name()));
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

    @Operation(
        operationId = "login",
        summary = "Exchange a username and password for a token",
        description = """
            On success the reply carries a signed token to send as `Authorization: Bearer \
            <token>` on subsequent requests. The token carries the tenant, which is why no \
            endpoint takes one as a parameter.

            Every failure — unknown account, wrong password, or an account in a tenant this \
            caller cannot reach — returns the same `401` with the same body. Distinguishing them \
            would turn this endpoint into an account-enumeration oracle.

            No permission is required.""")
    @ApiResponse(responseCode = "200", description = "Signed in.",
        content = @Content(mediaType = "application/json",
                           schema = @Schema(implementation = AuthResponse.class)))
    @ApiResponse(responseCode = "401",
        description = "The credentials were not accepted. The reply is identical whether the "
                    + "account does not exist, the password is wrong, or the account is locked.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "422",
        description = "The request omitted a username or a password.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "429",
        description = "Too many attempts. `Retry-After` gives the seconds to wait. Rate limits "
                    + "apply per account and per source address, so this does not confirm that "
                    + "the named account exists.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "400",
        description = "The request body could not be read.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "500",
        description = "Sign-in failed for a reason the caller cannot act on. Quote the "
                    + "`traceId` when reporting it.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req,
                                   HttpServletRequest httpRequest) {
        // The tenant has to be established before authenticating, because
        // authentication reads the users table and that table is behind the
        // tenant policy. An unknown username yields no tenant, which is
        // reported exactly like a wrong password.
        Optional<Long> tenantId = loginTenantResolver.resolveFor(req.username());
        if (tenantId.isEmpty()) {
            return rejectLogin(httpRequest);
        }

        try {
            TenantContextBinder.bind(tenantId.get());

            try {
                authManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.username(), req.password()));
            } catch (AuthenticationException e) {
                return rejectLogin(httpRequest);
            }

            var user = userRepo.findByUsername(req.username()).orElseThrow();
            String token = jwtTokenService.generateToken(user.getUsername(), tenantId.get());
            return ResponseEntity.ok(
                new AuthResponse(token, user.getUsername(), user.getRole().name()));
        } finally {
            // JwtFilter clears this for authenticated requests, but a login
            // arrives without a token and so never passes through that branch.
            TenantContextBinder.clear();
        }
    }

    /**
     * The single reply for every way a login can fail.
     *
     * <p>Built in one place so the three call sites cannot drift apart: a
     * response that differed by even a problem type would restore the
     * enumeration oracle {@link #GENERIC_LOGIN_FAILURE} exists to close.
     */
    private ResponseEntity<ProblemDetail> rejectLogin(HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiProblem.of(
            HttpStatus.UNAUTHORIZED, "invalid-credentials", "Invalid credentials",
            GENERIC_LOGIN_FAILURE, httpRequest));
    }
}
