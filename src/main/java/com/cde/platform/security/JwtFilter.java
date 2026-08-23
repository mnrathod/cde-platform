package com.cde.platform.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.cde.platform.tenancy.TenantContext;
import com.cde.platform.tenancy.TenantContextBinder;

import java.io.IOException;
import java.util.Optional;

@Component
public class JwtFilter extends OncePerRequestFilter {

    private final JwtTokenService jwtTokenService;
    private final UserDetailsService userDetailsService;

    public JwtFilter(JwtTokenService jwtTokenService, @Lazy UserDetailsService userDetailsService) {
        this.jwtTokenService = jwtTokenService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        // Request threads are pooled, so whatever this filter binds must be
        // undone — a tenant left behind is inherited by whichever unrelated
        // request picks the thread up next, which is a cross-tenant leak with
        // no bug anywhere near the code that suffers it.
        //
        // Restored rather than cleared, because this filter is not always the
        // outermost binder. An inner request dispatched while another context
        // is already established would otherwise return to an unbound thread,
        // and an unbound thread does not fail — it silently reads nothing.
        Optional<Long> previous = TenantContext.currentTenantId();
        try {
            authenticateFromBearerToken(req);
            chain.doFilter(req, res);
        } finally {
            TenantContextBinder.restore(previous);
        }
    }

    private void authenticateFromBearerToken(HttpServletRequest req) {
        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return;
        }

        String token = header.substring(7);
        if (!jwtTokenService.isTokenValid(token)) {
            return;
        }

        // Tenant context is established before the user is loaded, because
        // loading the user reads the users table — which is itself behind the
        // tenant policy. Without this the lookup returns nothing and a valid
        // token presents as an unknown user.
        jwtTokenService.extractTenantId(token).ifPresent(TenantContextBinder::bind);

        String username = jwtTokenService.extractUsername(token);
        UserDetails ud = userDetailsService.loadUserByUsername(username);
        var auth = new UsernamePasswordAuthenticationToken(ud, null, ud.getAuthorities());
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(req));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
