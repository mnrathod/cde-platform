package com.cde.platform.config;

import com.cde.platform.repository.UserRepository;
import com.cde.platform.security.JwtFilter;
import com.cde.platform.security.RolePermissions;
import com.cde.platform.security.SessionCookie;
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
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.header.writers.ContentSecurityPolicyHeaderWriter;
import org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy;
import org.springframework.security.web.header.writers.CrossOriginResourcePolicyHeaderWriter.CrossOriginResourcePolicy;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter;
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter.XFrameOptionsMode;
import com.cde.platform.security.ContentSecurityPolicyNonce;
import com.cde.platform.web.BrowserApplicationRoutes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.pathPattern;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.*;
import java.util.List;
import java.util.Set;

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
    private final SessionCookie sessionCookie;
    private final ResponseHeaderHardening responseHeaders;

    public SecurityConfig(JwtFilter jwtFilter,
                          UserRepository userRepo,
                          AuthenticationEntryPoint authenticationEntryPoint,
                          AccessDeniedHandler accessDeniedHandler,
                          WebSecurityHeadersProperties webProperties,
                          SessionCookie sessionCookie) {
        this.jwtFilter = jwtFilter;
        this.userRepo = userRepo;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.webProperties = webProperties;
        this.sessionCookie = sessionCookie;
        this.responseHeaders = new ResponseHeaderHardening(webProperties);
        webProperties.requireValidOrigins();
    }

    /** Methods that are not supposed to change anything, so need no token. */
    private static final Set<String> SAFE_METHODS =
        Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsSource()))
            // Switched on when the session moved into a cookie, and scoped to
            // exactly the requests that changed. A bearer header is not sent
            // by a browser on its own, so a cross-site form could never forge
            // an authenticated call while that was the only credential — which
            // is why disabling this was defensible before and is not now: an
            // ambient cookie rides along on a cross-site POST unless something
            // stops it. SameSite=Lax is that something, and §5.4 asks for a
            // token as well, on the principle that one mechanism is not a
            // control.
            //
            // withHttpOnlyFalse because the token is meant to be read by our
            // own script and echoed in a header; it is not a credential, it is
            // proof that the caller could read a value from this origin. The
            // session cookie beside it stays HttpOnly.
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .requireCsrfProtectionMatcher(this::needsCsrfToken))
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
                // The browser application's own pages, and the fingerprinted
                // files they load. These carry no data — the application
                // authenticates against /api like any other client, and every
                // API request is authorised on its own.
                //
                // The embed route in particular holds no session and must not
                // redirect to a login: ADR 14 makes the viewer authenticate
                // nobody, and a login form inside someone else's iframe is both
                // a broken integration and an invitation to type a password
                // into a frame. The framing decision is the CSP allow-list, not
                // this line.
                .requestMatchers(BrowserApplicationRoutes.ALL).permitAll()
                .requestMatchers(BrowserApplicationRoutes.EMBED + "/**").permitAll()
                .requestMatchers("/*.js", "/*.css", "/*.ico", "/*.webmanifest", "/*.txt",
                                 "/assets/**", "/icons/**", "/media/**").permitAll()
                // Named rather than "/api/auth/**": these two are the only
                // ones a caller reaches without a credential, and the wildcard
                // also opened /session and /logout, which exist to tell an
                // authenticated caller who they are and to end their session.
                // Both take an Authentication, so permitting them anonymously
                // meant handing the controller a null principal.
                .requestMatchers("/api/auth/login", "/api/auth/register",
                                 "/favicon.ico", "/favicon.png",
                                 "/api/logs/**").permitAll()
                .anyRequest().authenticated()
            )
            .headers(responseHeaders::hardenResponseHeaders)
            // Without these, a request refused by the filter chain never
            // reaches the controller advice, so 401 and 403 came back in a
            // different shape from every other error the API returns.
            .exceptionHandling(e -> e
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler))
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            // Before the header writer, because the policy it composes names
            // the nonce this generates. Registered here rather than as a plain
            // @Component so the ordering is stated where it matters instead of
            // depending on filter-registration defaults.
            .addFilterBefore(new ContentSecurityPolicyNonce(), HeaderWriterFilter.class);
        return http.build();
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

    /**
     * Which requests must carry a CSRF token.
     *
     * <p>Only the ones a browser could be tricked into making with a
     * credential it did not choose to send — that is, state-changing requests
     * authenticated by the session cookie. Three exclusions, each for a
     * reason:
     *
     * <ul>
     *   <li><b>Safe methods.</b> GET, HEAD, OPTIONS and TRACE are not supposed
     *       to change anything, and requiring a token on them would break
     *       ordinary navigation to no purpose.</li>
     *   <li><b>Bearer callers.</b> A browser never attaches an Authorization
     *       header by itself, so a request carrying one was constructed by a
     *       client that already had the token. Demanding CSRF of the mobile
     *       SDKs would break their published contract to defend against an
     *       attack they are not exposed to.</li>
     *   <li><b>Sign-in and registration.</b> They carry no session to abuse,
     *       and a login that required a token fetched beforehand would fail
     *       for anyone arriving with a cold cache.</li>
     * </ul>
     */
    private boolean needsCsrfToken(HttpServletRequest request) {
        if (SAFE_METHODS.contains(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        if ("/api/auth/login".equals(path) || "/api/auth/register".equals(path)) {
            return false;
        }
        return sessionCookie.authenticatesByCookie(request);
    }

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
