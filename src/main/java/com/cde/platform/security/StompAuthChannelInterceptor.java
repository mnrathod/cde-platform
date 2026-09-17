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

        UserDetails user = authenticate(accessor);
        if (user == null) {
            // Refusing the CONNECT closes the session, which is the point:
            // an unauthenticated socket must not be able to subscribe.
            throw new IllegalArgumentException("A valid token is required to connect.");
        }

        accessor.setUser(new UsernamePasswordAuthenticationToken(
            user, null, user.getAuthorities()));
        return message;
    }

    /** @return the authenticated user, or null when the token is absent or invalid */
    private UserDetails authenticate(StompHeaderAccessor accessor) {
        String token = bearerToken(accessor.getNativeHeader(AUTH_HEADER));
        if (token == null) {
            token = handshakeToken(accessor);
        }
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
