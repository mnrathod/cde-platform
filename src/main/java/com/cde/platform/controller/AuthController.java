package com.cde.platform.controller;

import com.cde.platform.dto.Dtos.*;
import com.cde.platform.model.User;
import com.cde.platform.repository.UserRepository;
import com.cde.platform.security.JwtUtil;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.authentication.*;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepo;
    private final PasswordEncoder encoder;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authManager;

    public AuthController(UserRepository userRepo, PasswordEncoder encoder,
                          JwtUtil jwtUtil, AuthenticationManager authManager) {
        this.userRepo = userRepo;
        this.encoder = encoder;
        this.jwtUtil = jwtUtil;
        this.authManager = authManager;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest req) {
        if (userRepo.existsByUsername(req.username()))
            return ResponseEntity.badRequest().body("Username already taken");
        if (userRepo.existsByEmail(req.email()))
            return ResponseEntity.badRequest().body("Email already registered");

        var user = User.builder()
            .username(req.username())
            .email(req.email())
            .password(encoder.encode(req.password()))
            .role(req.role() != null ? req.role() : User.Role.ENGINEER)
            .build();
        userRepo.save(user);

        String token = jwtUtil.generateToken(user.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(new AuthResponse(token, user.getUsername(), user.getRole().name()));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req) {
        try {
            authManager.authenticate(
                new UsernamePasswordAuthenticationToken(req.username(), req.password()));
        } catch (AuthenticationException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid credentials");
        }
        var user = userRepo.findByUsername(req.username()).orElseThrow();
        String token = jwtUtil.generateToken(user.getUsername());
        return ResponseEntity.ok(new AuthResponse(token, user.getUsername(), user.getRole().name()));
    }
}
