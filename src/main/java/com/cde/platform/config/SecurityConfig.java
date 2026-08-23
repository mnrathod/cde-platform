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
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtFilter jwtFilter;
    private final UserRepository userRepo;
    private final AuthenticationEntryPoint authenticationEntryPoint;
    private final AccessDeniedHandler accessDeniedHandler;

    public SecurityConfig(JwtFilter jwtFilter,
                          UserRepository userRepo,
                          AuthenticationEntryPoint authenticationEntryPoint,
                          AccessDeniedHandler accessDeniedHandler) {
        this.jwtFilter = jwtFilter;
        this.userRepo = userRepo;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
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
                // The specification and the docs page describe the API's shape,
                // not its contents, and both are read by tooling that has no
                // credential — a client generator, a linter, a reviewer. The
                // "try it" console is off by default
                // (springdoc.swagger-ui.supported-submit-methods), so the page
                // documents without also being a request console.
                //
                // The .yaml and .json variants are separate paths rather than
                // children of the base one: "/api/openapi/**" does not match
                // "/api/openapi.yaml", so the YAML the build compares against
                // was being refused until it was named here.
                // /api/docs redirects to /api/swagger-ui/index.html — the UI's
                // assets are served under the docs path's own prefix, not at
                // the root. Permitting only "/swagger-ui/**" left the docs
                // page reachable and its every asset refused, so it rendered
                // as a blank frame.
                .requestMatchers("/api/openapi", "/api/openapi.yaml", "/api/openapi/**",
                                 "/api/docs", "/api/docs/**",
                                 "/api/swagger-ui/**", "/swagger-ui/**").permitAll()
                // /api/ai/** is no longer here. It forwards a caller-supplied
                // body to a third-party model provider and spends this
                // deployment's credit doing it — unauthenticated, that is an
                // open relay billed to us, and it forwards whatever it is
                // handed to an outside service. Authentication is the floor,
                // not the fix: the payload sanitiser the data-handling rules
                // require is still to be built.
                //
                // /api/logs/** stays open deliberately. An error worth
                // reporting often happens when the session has already failed,
                // so requiring a credential would lose exactly the reports
                // worth having. Its input is bounded and stripped of line
                // breaks before it reaches a log.
                .requestMatchers("/api/auth/**",
                                 "/", "/index.html", "/viewer.html",
                                 "/favicon.ico", "/favicon.png",
                                 "/api/logs/**",
                                 "/js/**", "/css/**", "/img/**").permitAll()
                .anyRequest().authenticated()
            )
            // frameOptions stays on: it was disabled only so the H2 console
            // could render in a frame, and leaving it off invites clickjacking
            // of the viewer.
            .headers(h -> h.frameOptions(fo -> fo.sameOrigin()))
            // Without these, a request refused by the filter chain never
            // reaches the controller advice, so 401 and 403 came back in a
            // different shape from every other error the API returns.
            .exceptionHandling(e -> e
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler))
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
