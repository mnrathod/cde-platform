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

/**
 * Authenticates a STOMP session from the JWT the client sends on CONNECT.
 *
 * <p>The WebSocket handshake is a plain GET that a browser cannot attach an
 * {@code Authorization} header to, so the usual servlet filter never sees a
 * token and the socket would otherwise be anonymous — meaning anyone who
 * could reach the server could subscribe to any document's collaboration
 * traffic. The token travels in the CONNECT frame instead, and the
 * authenticated user is attached to the session so every later frame from it
 * carries a known identity.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompAuthChannelInterceptor.class);

    private static final String AUTH_HEADER  = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtil            jwtUtil;
    private final UserDetailsService userDetailsService;

    public StompAuthChannelInterceptor(JwtUtil jwtUtil,
                                       @Lazy UserDetailsService userDetailsService) {
        this.jwtUtil            = jwtUtil;
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
        if (token == null || !jwtUtil.validateToken(token)) return null;

        try {
            return userDetailsService.loadUserByUsername(jwtUtil.extractUsername(token));
        } catch (Exception e) {
            // The token was well-formed but names nobody — a deleted account,
            // most likely. Logged without the token itself.
            log.warn("Rejected a WebSocket connection: {}", e.getMessage());
            return null;
        }
    }

    private String bearerToken(List<String> headerValues) {
        if (headerValues == null || headerValues.isEmpty()) return null;
        String value = headerValues.get(0);
        return value != null && value.startsWith(BEARER_PREFIX)
            ? value.substring(BEARER_PREFIX.length())
            : null;
    }
}
