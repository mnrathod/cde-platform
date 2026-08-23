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
import com.cde.platform.model.Tenant;
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
    private final TenantRepository tenantRepo;
    private final PasswordEncoder encoder;
    private final JwtTokenService jwtTokenService;
    private final AuthenticationManager authManager;
    private final LoginTenantResolver loginTenantResolver;
    private final TenancyProperties tenancyProperties;

    public AuthController(UserRepository userRepo,
                          TenantRepository tenantRepo,
                          PasswordEncoder encoder,
                          JwtTokenService jwtTokenService,
                          AuthenticationManager authManager,
                          LoginTenantResolver loginTenantResolver,
                          TenancyProperties tenancyProperties) {
        this.userRepo = userRepo;
        this.tenantRepo = tenantRepo;
        this.encoder = encoder;
        this.jwtTokenService = jwtTokenService;
        this.authManager = authManager;
        this.loginTenantResolver = loginTenantResolver;
        this.tenancyProperties = tenancyProperties;
    }

    /**
     * Registers into the default tenant.
     *
     * <p>Self-service registration cannot choose its own tenant — accepting a
     * tenant identifier from the request body would let any caller create an
     * account inside someone else's organisation. Provisioning a user into a
     * specific tenant is an administrative operation, and joining one by
     * invitation or verified email domain is home-realm discovery, which is not
     * built yet.
     */
    @Operation(
        operationId = "register",
        summary = "Create an account in the default tenant",
        description = """
            Self-service registration. The account is created in the deployment's default tenant \
            with the `ENGINEER` role unless another is asked for.

            A tenant cannot be chosen here. Accepting a tenant identifier would let any caller \
            create an account inside another organisation; joining a specific tenant is either an \
            administrative action or home-realm discovery by verified email domain.

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
    @ApiResponse(responseCode = "409",
        description = "The username or the email address is already in use in that tenant.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "422",
        description = "The details failed validation — most often a password shorter than the "
                    + "tenant's minimum, or one found in a known-breached set.",
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
        Tenant defaultTenant = tenantRepo.findBySlug(tenancyProperties.getDefaultTenantSlug())
            .orElseThrow(() -> new IllegalStateException(
                "Default tenant '" + tenancyProperties.getDefaultTenantSlug() + "' is missing"));

        return TenantContext.callAsTenant(defaultTenant.getId(), () -> {
            if (userRepo.existsByUsername(req.username()))
                return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiProblem.of(
                    HttpStatus.CONFLICT, "username-taken", "Username taken",
                    "That username is already in use. Choose another.", httpRequest));
            if (userRepo.existsByEmail(req.email()))
                return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiProblem.of(
                    HttpStatus.CONFLICT, "email-registered", "Email already registered",
                    "That email address already has an account. Sign in instead, or reset the password.",
                    httpRequest));

            var user = User.builder()
                .username(req.username())
                .email(req.email())
                .password(encoder.encode(req.password()))
                .role(req.role() != null ? req.role() : User.Role.ENGINEER)
                .tenantId(defaultTenant.getId())
                .build();
            userRepo.save(user);

            String token = jwtTokenService.generateToken(user.getUsername(), defaultTenant.getId());
            return ResponseEntity.status(HttpStatus.CREATED)
                .body(new AuthResponse(token, user.getUsername(), user.getRole().name()));
        });
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
