package com.cde.platform.config;

import com.cde.platform.repository.UserRepository;
import com.cde.platform.security.JwtFilter;
import com.cde.platform.security.RolePermissions;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.*;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer.HstsConfig;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ContentSecurityPolicyHeaderWriter;
import org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy;
import org.springframework.security.web.header.writers.CrossOriginResourcePolicyHeaderWriter.CrossOriginResourcePolicy;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.pathPattern;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.*;
import java.util.List;

@Configuration
@EnableWebSecurity
// Turns on @PreAuthorize. Without it the annotations are inert decoration —
// present in the source, checked by nothing, and indistinguishable from a real
// control to anyone reading the code.
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtFilter jwtFilter;
    private final UserRepository userRepo;
    private final AuthenticationEntryPoint authenticationEntryPoint;
    private final AccessDeniedHandler accessDeniedHandler;
    private final WebSecurityHeadersProperties webProperties;

    public SecurityConfig(JwtFilter jwtFilter,
                          UserRepository userRepo,
                          AuthenticationEntryPoint authenticationEntryPoint,
                          AccessDeniedHandler accessDeniedHandler,
                          WebSecurityHeadersProperties webProperties) {
        this.jwtFilter = jwtFilter;
        this.userRepo = userRepo;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.webProperties = webProperties;
        webProperties.rejectWildcardOrigins();
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
                //
                // The static demonstration page that used to be permitted
                // here is gone. It was a standalone UI from the first commit,
                // superseded by the Angular application, and it printed both
                // seeded accounts' passwords on the page and prefilled one
                // into the password input's value attribute. The paths it
                // needed (/index.html, /viewer.html, /js, /css, /img) went
                // with it rather than being left as permits for files that no
                // longer exist.
                .requestMatchers("/api/auth/**",
                                 "/favicon.ico", "/favicon.png",
                                 "/api/logs/**").permitAll()
                .anyRequest().authenticated()
            )
            .headers(this::hardenResponseHeaders)
            // Without these, a request refused by the filter chain never
            // reaches the controller advice, so 401 and 403 came back in a
            // different shape from every other error the API returns.
            .exceptionHandling(e -> e
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler))
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * The API's own content-security policy.
     *
     * <p>This server answers with JSON, so it has no legitimate need to load a
     * script, a stylesheet, a frame or a plugin from anywhere — {@code 'none'}
     * is both the strictest policy and the accurate one. It matters on the
     * paths that <em>do</em> return markup: an error page, and anything a
     * future misconfiguration causes to be rendered rather than serialised.
     *
     * <p>{@code frame-ancestors 'none'} is the modern replacement for
     * X-Frame-Options and is what actually stops the viewer being framed by a
     * site that wants a user's clicks; both are sent, because older browsers
     * read only the latter.
     */
    private static final String API_CONTENT_SECURITY_POLICY =
        "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    /**
     * A narrower relaxation for the API documentation page only.
     *
     * <p>Swagger UI is a real single-page application: it loads its bundle
     * from this origin and applies inline styles as it renders. {@code
     * style-src 'unsafe-inline'} is therefore unavoidable for it to display,
     * and is scoped to this one path rather than granted everywhere.
     *
     * <p>The concession is to <em>styles</em>, not scripts. An inline style
     * cannot execute JavaScript; {@code script-src 'self'} still refuses any
     * injected script, which is the property that matters. The alternative —
     * relaxing the global policy — would have weakened every path in order to
     * render one developer-facing page.
     */
    private static final String DOCS_CONTENT_SECURITY_POLICY =
        "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
        + "img-src 'self' data:; font-src 'self'; connect-src 'self'; "
        + "frame-ancestors 'none'; object-src 'none'; base-uri 'self'";

    /** Paths whose responses are markup rendered by a browser. */
    private static final RequestMatcher DOCUMENTATION_PAGES =
        new OrRequestMatcher(pathPattern("/api/docs/**"),
                             pathPattern("/api/docs"),
                             pathPattern("/api/swagger-ui/**"),
                             pathPattern("/swagger-ui/**"));

    /**
     * The response headers a browser needs in order to apply the protections
     * it already implements.
     *
     * <p>Spring Security supplies {@code nosniff}, {@code Cache-Control:
     * no-store} and X-Frame-Options by default; the rest were simply absent,
     * so a browser talking to this API enforced no transport policy, no
     * content policy, no referrer policy and no feature policy.
     */
    private void hardenResponseHeaders(HeadersConfigurer<HttpSecurity> headers) {
        headers
            // Retained from the previous configuration: the viewer must not be
            // framable by another origin. frame-ancestors in the CSP above is
            // the directive that modern browsers actually honour.
            .frameOptions(frame -> frame.sameOrigin())
            .referrerPolicy(referrer -> referrer.policy(
                ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
            // Isolates this origin's browsing context, so a window it opens —
            // or that opens it — cannot reach into it.
            .crossOriginOpenerPolicy(policy -> policy.policy(
                CrossOriginOpenerPolicy.SAME_ORIGIN))
            .crossOriginResourcePolicy(policy -> policy.policy(
                CrossOriginResourcePolicy.SAME_ORIGIN))
            // Two policies, chosen by path: the strict one everywhere, and the
            // documentation relaxation only on the documentation paths. A
            // single global policy would have to be the looser of the two.
            .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                DOCUMENTATION_PAGES,
                new ContentSecurityPolicyHeaderWriter(documentationPolicy())))
            .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                new NegatedRequestMatcher(DOCUMENTATION_PAGES),
                new ContentSecurityPolicyHeaderWriter(apiPolicy())));

        // Not chained with the rest: permissionsPolicy returns its own config
        // object rather than the HeadersConfigurer, so it terminates a chain
        // instead of continuing one.
        headers.permissionsPolicy(permissions -> permissions.policy(
            "accelerometer=(), autoplay=(), camera=(), display-capture=(), "
            + "encrypted-media=(), fullscreen=(self), geolocation=(), gyroscope=(), "
            + "magnetometer=(), microphone=(), midi=(), payment=(), "
            + "picture-in-picture=(), usb=(), xr-spatial-tracking=()"));

        if (webProperties.isHstsEnabled()) {
            headers.httpStrictTransportSecurity(hsts -> hsts
                .maxAgeInSeconds(webProperties.getHstsMaxAgeSeconds())
                .includeSubDomains(true)
                .preload(true));
        } else {
            headers.httpStrictTransportSecurity(HstsConfig::disable);
        }
    }

    private String apiPolicy() {
        return withReportUri(API_CONTENT_SECURITY_POLICY);
    }

    private String documentationPolicy() {
        return withReportUri(DOCS_CONTENT_SECURITY_POLICY);
    }

    private String withReportUri(String policy) {
        String reportUri = webProperties.getCspReportUri();
        return reportUri.isBlank() ? policy : policy + "; report-uri " + reportUri;
    }

    /**
     * The principal carries its permissions, not only its role.
     *
     * <p>{@code .roles(...)} was granting a single {@code ROLE_} authority, so
     * the permission each endpoint documents as its requirement was checkable
     * nowhere — the only real gate was "is this request authenticated". The
     * authorities now include both, so a rule written against a role and a
     * rule written against a permission both resolve.
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return username -> userRepo.findByUsername(username)
            .map(u -> User.withUsername(u.getUsername())
                .password(u.getPassword())
                .authorities(RolePermissions.authoritiesFor(u.getRole()))
                .build())
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }

    /**
     * Which other origins a browser may let call this API, and with what.
     *
     * <p>This was {@code allowedOrigins("*")} with {@code allowedHeaders("*")},
     * which is the configuration that makes a browser's same-origin policy
     * stop applying: any page anywhere could issue a request here and read the
     * reply. The default is now the opposite — no origin, which registers no
     * CORS configuration at all rather than one that permits everything.
     *
     * <p>A same-origin deployment (the Angular build and this API behind one
     * web tier, which is how it is meant to run) needs no entry here. Only a
     * genuinely separate front-end origin does, and it must be named.
     *
     * <p>The permitted headers are enumerated rather than reflected. {@code
     * "*"} tells the browser to allow whatever the caller asks for, which
     * makes the allow-list a formality.
     */
    @Bean
    public CorsConfigurationSource corsSource() {
        var source = new UrlBasedCorsConfigurationSource();
        if (!webProperties.hasCrossOriginCallers()) {
            return source;
        }
        var config = new CorsConfiguration();
        config.setAllowedOrigins(webProperties.getAllowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept",
                                         "X-Requested-With", "Idempotency-Key"));
        // What a browser is allowed to hand back to the calling script. Without
        // this the client cannot read the correlation identifier it is meant to
        // quote to support, nor the rate-limit headers it is meant to obey.
        config.setExposedHeaders(List.of("Location", "Retry-After", "X-Trace-Id",
                                         "X-RateLimit-Limit", "X-RateLimit-Remaining",
                                         "X-RateLimit-Reset"));
        config.setAllowCredentials(true);
        config.setMaxAge(1800L);
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
