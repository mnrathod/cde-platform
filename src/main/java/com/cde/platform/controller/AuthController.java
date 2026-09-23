package com.cde.platform.controller;

import com.cde.platform.audit.AuditAction;
import com.cde.platform.audit.AuditOutcome;
import com.cde.platform.audit.AuditRequest;
import com.cde.platform.audit.AuditableChange;
import com.cde.platform.audit.RequestAuditor;
import com.cde.platform.dto.AuthDtos.*;
import com.cde.platform.exception.ApiProblem;
import com.cde.platform.openapi.ApiDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.cde.platform.model.User;
import com.cde.platform.repository.TenantRepository;
import com.cde.platform.repository.UserRepository;
import com.cde.platform.security.AuthenticationThrottle;
import com.cde.platform.security.JwtTokenService;
import com.cde.platform.security.SessionCookie;
import com.cde.platform.tenancy.LoginTenantResolver;
import com.cde.platform.tenancy.TenancyProperties;
import com.cde.platform.tenancy.TenantContext;
import com.cde.platform.tenancy.TenantContextBinder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
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
    private final RequestAuditor auditor;
    private final AuthenticationThrottle throttle;
    private final SessionCookie sessionCookie;

    public AuthController(UserRepository userRepo,
                          JwtTokenService jwtTokenService,
                          AuthenticationManager authManager,
                          LoginTenantResolver loginTenantResolver,
                          RequestAuditor auditor,
                          AuthenticationThrottle throttle,
                          SessionCookie sessionCookie) {
        this.userRepo = userRepo;
        this.jwtTokenService = jwtTokenService;
        this.authManager = authManager;
        this.loginTenantResolver = loginTenantResolver;
        this.auditor = auditor;
        this.throttle = throttle;
        this.sessionCookie = sessionCookie;
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
        // Throttled before anything is looked up. Checking first is what makes
        // this a rate limit rather than a report: the expensive work — a tenant
        // lookup and 600,000 rounds of PBKDF2 — is exactly what an attacker
        // wants to make the server do, so it must not happen for a request that
        // is going to be refused anyway.
        String source = httpRequest.getRemoteAddr();
        var decision = throttle.evaluate(req.username(), source);
        if (decision.isThrottled()) {
            return tooManyAttempts(decision, httpRequest);
        }

        // The tenant has to be established before authenticating, because
        // authentication reads the users table and that table is behind the
        // tenant policy. An unknown username yields no tenant, which is
        // reported exactly like a wrong password.
        Optional<Long> tenantId = loginTenantResolver.resolveFor(req.username());
        if (tenantId.isEmpty()) {
            // No tenant to attribute this to, so it goes to the application
            // log rather than any organisation's trail. Writing it to a
            // guessed tenant would disclose across the boundary, and this is
            // also the shape a credential-stuffing run takes — many usernames
            // that match nothing — which is a cross-tenant pattern the SIEM
            // sees and a single tenant's trail could not.
            auditor.recordWithoutTenant(
                AuditRequest.byUnauthenticated(req.username())
                    .did(AuditAction.SIGN_IN)
                    .outcome(AuditOutcome.FAILURE),
                httpRequest);
            // Counted even though the account does not exist. Otherwise
            // probing for valid usernames is unthrottled, and the difference in
            // how fast the two cases are refused becomes the oracle the single
            // error message exists to close.
            throttle.recordFailure(req.username(), source);
            return rejectLogin(httpRequest);
        }

        try {
            TenantContextBinder.bind(tenantId.get());

            try {
                authManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.username(), req.password()));
            } catch (AuthenticationException e) {
                // The tenant is known here, so this one does reach the
                // organisation's own trail — a tenant administrator should be
                // able to see failed attempts against their accounts, and that
                // is not visible to the caller either way.
                auditor.record(
                    AuditRequest.byUnauthenticated(req.username())
                        .did(AuditAction.SIGN_IN)
                        .outcome(AuditOutcome.FAILURE),
                    httpRequest);
                throttle.recordFailure(req.username(), source);
                return rejectLogin(httpRequest);
            }

            var user = userRepo.findByUsername(req.username()).orElseThrow();
            // The account's counter clears; the source's does not. One success
            // does not make a host working through a credential list
            // legitimate — if it did, an attacker would interleave a
            // known-good login to reset the penalty.
            throttle.recordSuccess(req.username());
            auditor.record(
                AuditRequest.by(user.getId(), user.getUsername())
                    .did(AuditAction.SIGN_IN)
                    .outcome(AuditOutcome.SUCCESS)
                    .to("User", user.getId()),
                httpRequest);

            String token = jwtTokenService.generateToken(user.getUsername(), tenantId.get());
            // Both, deliberately. The cookie is what the browser will use and
            // is the one a script cannot read (§4.6); the body token is the
            // mobile SDKs' published contract, and a native client has no
            // cookie jar to protect. See SessionCookie.
            return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookie.issueFor(token).toString())
                .body(new AuthResponse(token, user.getUsername(), user.getRole().name()));
        } finally {
            // JwtFilter clears this for authenticated requests, but a login
            // arrives without a token and so never passes through that branch.
            TenantContextBinder.clear();
        }
    }

    @Operation(
        operationId = "getSession",
        summary = "Who the current session belongs to",
        description = """
            Names the account this request is authenticated as. It exists because a browser \
            session lives in an `HttpOnly` cookie: the client cannot read the token, so it \
            cannot read the subject out of it either, and asking the server is the only honest \
            way to find out who is signed in.

            Deliberately returns no token. Handing the same value back in a body would put it \
            within reach of any script on the page and undo the reason for the cookie.

            Requires only a valid session; any authenticated caller may ask about their own.""")
    @ApiResponse(responseCode = "200", description = "The session's account.",
        content = @Content(mediaType = "application/json",
                           schema = @Schema(implementation = SessionResponse.class)))
    @ApiResponse(responseCode = "401",
        description = "No credential was presented, or the one presented is expired or invalid.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "500",
        description = "The session could not be read for a reason the caller cannot act on. "
                    + "Quote the `traceId` when reporting it.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @GetMapping("/session")
    public ResponseEntity<?> session(Authentication authentication) {
        return userRepo.findByUsername(authentication.getName())
            .<ResponseEntity<?>>map(user -> ResponseEntity.ok(
                new SessionResponse(user.getUsername(), user.getRole().name())))
            // Authenticated against a user row that is no longer readable —
            // deleted, or moved out of this tenant mid-session. Treated as no
            // session rather than as a server fault, because that is what it
            // is from the caller's side, and the cookie is cleared so the
            // browser stops presenting a credential that resolves to nobody.
            .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.SET_COOKIE, sessionCookie.clear().toString())
                .build());
    }

    @Operation(
        operationId = "logout",
        summary = "End the browser session",
        description = """
            Clears the session cookie. Needed because the cookie is `HttpOnly`: script cannot \
            delete what script cannot see, so signing out has to be something the server does.

            Bearer callers have nothing to clear — a token is valid until it expires — so for \
            them this records the event and does nothing else. Revoking a bearer token before \
            its expiry needs a revocation list, which is §4.6's separate concern.

            Requires only a valid session.""")
    @ApiResponse(responseCode = "204", description = "Signed out.")
    @ApiResponse(responseCode = "401",
        description = "No credential was presented, or the one presented is expired or invalid.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "500",
        description = "Sign-out failed for a reason the caller cannot act on. Quote the "
                    + "`traceId` when reporting it.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(Authentication authentication,
                                       HttpServletRequest httpRequest) {
        userRepo.findByUsername(authentication.getName()).ifPresent(user ->
            auditor.record(
                AuditRequest.by(user.getId(), user.getUsername())
                    .did(AuditAction.SIGN_OUT)
                    .outcome(AuditOutcome.SUCCESS)
                    .to("User", user.getId()),
                httpRequest));

        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, sessionCookie.clear().toString())
            .build();
    }

    /**
     * The single reply for every way a login can fail.
     *
     * <p>Built in one place so the three call sites cannot drift apart: a
     * response that differed by even a problem type would restore the
     * enumeration oracle {@link #GENERIC_LOGIN_FAILURE} exists to close.
     */
    /**
     * The reply when the caller is being slowed down.
     *
     * <p>Carries {@code Retry-After} so a well-behaved client waits rather than
     * retrying immediately, and says nothing about whether the named account
     * exists — the limit applies per account and per source alike, so a 429
     * confirms nothing either way.
     */
    private ResponseEntity<ProblemDetail> tooManyAttempts(
            AuthenticationThrottle.Decision decision, HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()))
            .body(ApiProblem.of(HttpStatus.TOO_MANY_REQUESTS, "too-many-attempts",
                "Too many attempts",
                "Too many sign-in attempts. Wait " + decision.retryAfterSeconds()
                    + " seconds and try again.", httpRequest));
    }

    private ResponseEntity<ProblemDetail> rejectLogin(HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiProblem.of(
            HttpStatus.UNAUTHORIZED, "invalid-credentials", "Invalid credentials",
            GENERIC_LOGIN_FAILURE, httpRequest));
    }
}
