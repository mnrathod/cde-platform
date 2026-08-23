package com.cde.platform.controller;

import com.cde.platform.dto.Dtos.*;
import com.cde.platform.model.Tenant;
import com.cde.platform.model.User;
import com.cde.platform.repository.TenantRepository;
import com.cde.platform.repository.UserRepository;
import com.cde.platform.security.JwtTokenService;
import com.cde.platform.tenancy.LoginTenantResolver;
import com.cde.platform.tenancy.TenancyProperties;
import com.cde.platform.tenancy.TenantContext;
import com.cde.platform.tenancy.TenantContextBinder;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.authentication.*;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
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
    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest req) {
        Tenant defaultTenant = tenantRepo.findBySlug(tenancyProperties.getDefaultTenantSlug())
            .orElseThrow(() -> new IllegalStateException(
                "Default tenant '" + tenancyProperties.getDefaultTenantSlug() + "' is missing"));

        return TenantContext.callAsTenant(defaultTenant.getId(), () -> {
            if (userRepo.existsByUsername(req.username()))
                return ResponseEntity.badRequest().body("Username already taken");
            if (userRepo.existsByEmail(req.email()))
                return ResponseEntity.badRequest().body("Email already registered");

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

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req) {
        // The tenant has to be established before authenticating, because
        // authentication reads the users table and that table is behind the
        // tenant policy. An unknown username yields no tenant, which is
        // reported exactly like a wrong password.
        Optional<Long> tenantId = loginTenantResolver.resolveFor(req.username());
        if (tenantId.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(GENERIC_LOGIN_FAILURE);
        }

        try {
            TenantContextBinder.bind(tenantId.get());

            try {
                authManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.username(), req.password()));
            } catch (AuthenticationException e) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(GENERIC_LOGIN_FAILURE);
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
}
