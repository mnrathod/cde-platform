package com.cde.platform.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Authenticates a STOMP session from the JWT the client sends on CONNECT.
 *
 * <p>The WebSocket handshake is a plain GET that a browser cannot attach an
 * {@code Authorization} header to, so the usual servlet filter never sees a
 * token and the socket would otherwise be anonymous — meaning anyone who
 * could reach the server could subscribe to any document's collaboration
 * traffic. The authenticated user is attached to the session so every later
 * frame from it carries a known identity.
 *
 * <p>Two places the credential can come from, for the same reason there are
 * two elsewhere. A native client sends it on the CONNECT frame, which is its
 * published contract. A browser has no token to send — the web client holds
 * none since the session moved into an {@code HttpOnly} cookie (§4.6) — so its
 * credential rides the handshake as that cookie and {@link
 * SessionCookieHandshake} leaves it where this can find it.
 *
 * <p>The CONNECT frame wins when both are present, matching {@link JwtFilter}:
 * a client that went to the trouble of naming an identity should not be
 * overruled by an ambient one.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompAuthChannelInterceptor.class);

    private static final String AUTH_HEADER  = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    /** Where {@link #rememberTenant} leaves the tenant for later frames. */
    public static final String TENANT_ATTRIBUTE = "cde.tenantId";

    private final JwtTokenService            jwtTokenService;
    private final UserDetailsService userDetailsService;

    public StompAuthChannelInterceptor(JwtTokenService jwtTokenService,
                                       @Lazy UserDetailsService userDetailsService) {
        this.jwtTokenService            = jwtTokenService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
            MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }

        String token = credential(accessor);
        UserDetails user = authenticate(token);
        if (user == null) {
            // Refusing the CONNECT closes the session, which is the point:
            // an unauthenticated socket must not be able to subscribe.
            throw new IllegalArgumentException("A valid token is required to connect.");
        }

        rememberTenant(accessor, token);
        accessor.setUser(new UsernamePasswordAuthenticationToken(
            user, null, user.getAuthorities()));
        return message;
    }

    /**
     * Records the connecting session's tenant, for later frames to be checked
     * against.
     *
     * <p>Authenticating the socket says who is on it, and says nothing about
     * what they may subscribe to. The destinations carry a document id and
     * nothing else, so without the tenant recorded here there is no way to tell
     * one tenant's document 41 from another's — see
     * {@link com.cde.platform.collaboration.CollaborationDestinationAuthorisation},
     * which reads it.
     *
     * <p>On the session rather than re-derived per frame because the token is
     * only in hand at CONNECT: later frames carry no credential.
     */
    private void rememberTenant(StompHeaderAccessor accessor, String token) {
        Map<String, Object> attributes = accessor.getSessionAttributes();
        if (attributes == null || token == null) return;
        jwtTokenService.extractTenantId(token)
            .ifPresent(tenantId -> attributes.put(TENANT_ATTRIBUTE, tenantId));
    }

    /** The token the CONNECT frame names, or the one the handshake carried. */
    private String credential(StompHeaderAccessor accessor) {
        String token = bearerToken(accessor.getNativeHeader(AUTH_HEADER));
        return token != null ? token : handshakeToken(accessor);
    }

    /** @return the authenticated user, or null when the token is absent or invalid */
    private UserDetails authenticate(String token) {
        if (token == null || !jwtTokenService.isTokenValid(token)) return null;

        try {
            return userDetailsService.loadUserByUsername(jwtTokenService.extractUsername(token));
        } catch (Exception e) {
            // The token was well-formed but names nobody — a deleted account,
            // most likely. Logged without the token itself.
            log.warn("Rejected a WebSocket connection: {}", e.getMessage());
            return null;
        }
    }

    /** The session cookie lifted off the handshake, if there was one. */
    private String handshakeToken(StompHeaderAccessor accessor) {
        Map<String, Object> attributes = accessor.getSessionAttributes();
        if (attributes == null) return null;
        Object token = attributes.get(SessionCookieHandshake.TOKEN_ATTRIBUTE);
        return token instanceof String value && !value.isBlank() ? value : null;
    }

    private String bearerToken(List<String> headerValues) {
        if (headerValues == null || headerValues.isEmpty()) return null;
        String value = headerValues.get(0);
        return value != null && value.startsWith(BEARER_PREFIX)
            ? value.substring(BEARER_PREFIX.length())
            : null;
    }
}
