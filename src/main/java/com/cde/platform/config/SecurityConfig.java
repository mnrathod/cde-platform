package com.cde.platform.config;

import com.cde.platform.repository.UserRepository;
import com.cde.platform.security.JwtFilter;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.*;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtFilter jwtFilter;
    private final UserRepository userRepo;

    public SecurityConfig(JwtFilter jwtFilter, UserRepository userRepo) {
        this.jwtFilter = jwtFilter;
        this.userRepo = userRepo;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // The WebSocket handshake is a plain GET with no Authorization
                // header — a browser cannot set one. It is authenticated on
                // the STOMP CONNECT frame instead, by
                // StompAuthChannelInterceptor, which rejects the session
                // outright without a valid token.
                .requestMatchers("/ws/**").permitAll()
                // Spring forwards unhandled errors to /error as a fresh
                // dispatch that carries no authentication. Requiring auth
                // there replaced the real status with an empty 403, so a 404
                // and a malformed body both reported themselves as a
                // permissions failure. The error body itself exposes no
                // detail: stack traces stay off by Boot's default.
                .requestMatchers("/error").permitAll()
                // Kubernetes sends no credentials, so the two probes must be
                // open. They carry no detail — only UP or DOWN — because
                // show-details is when-authorized.
                .requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
                // Everything else Actuator exposes is operational data:
                // metrics reveal traffic shape and the Prometheus endpoint
                // enumerates every route. Admin only.
                .requestMatchers(EndpointRequest.toAnyEndpoint()).hasRole("ADMIN")
                // /h2-console is gone with H2 itself. It was permitAll, which
                // meant an unauthenticated SQL console on any deployment where
                // the console was enabled — and it was enabled unconditionally.
                .requestMatchers("/api/auth/**",
                                 "/", "/index.html", "/viewer.html",
                                 "/favicon.ico", "/favicon.png",
                                 "/api/ai/**",
                                 "/api/logs/**",
                                 "/js/**", "/css/**", "/img/**").permitAll()
                .anyRequest().authenticated()
            )
            // frameOptions stays on: it was disabled only so the H2 console
            // could render in a frame, and leaving it off invites clickjacking
            // of the viewer.
            .headers(h -> h.frameOptions(fo -> fo.sameOrigin()))
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return username -> userRepo.findByUsername(username)
            .map(u -> User.withUsername(u.getUsername())
                .password(u.getPassword())
                .roles(u.getRole().name())
                .build())
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsSource() {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
