package com.cde.platform.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A WebSocket cannot carry an Authorization header through its handshake, so
 * the token arrives on the STOMP CONNECT frame. If that check were missing or
 * lenient, anyone able to reach the server could subscribe to any document's
 * collaboration traffic — so the refusals matter more than the happy path.
 */
class StompAuthChannelInterceptorTest {

    private static final String VALID_TOKEN = "valid.jwt.token";

    private JwtUtil            jwtUtil;
    private UserDetailsService userDetailsService;
    private StompAuthChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        jwtUtil            = mock(JwtUtil.class);
        userDetailsService = mock(UserDetailsService.class);
        interceptor        = new StompAuthChannelInterceptor(jwtUtil, userDetailsService);

        when(jwtUtil.validateToken(VALID_TOKEN)).thenReturn(true);
        when(jwtUtil.extractUsername(VALID_TOKEN)).thenReturn("ada");
        when(userDetailsService.loadUserByUsername("ada")).thenReturn(
            new User("ada", "", List.of(new SimpleGrantedAuthority("ROLE_ENGINEER"))));
    }

    private Message<?> frame(StompCommand command, String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (authorization != null) accessor.setNativeHeader("Authorization", authorization);
        accessor.setLeaveMutable(true);
        return org.springframework.messaging.support.MessageBuilder
            .createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<?> send(Message<?> message) {
        return interceptor.preSend(message, mock(org.springframework.messaging.MessageChannel.class));
    }

    @Test
    @DisplayName("a valid token attaches the user to the session")
    void validTokenAuthenticates() {
        Message<?> result = send(frame(StompCommand.CONNECT, "Bearer " + VALID_TOKEN));

        var accessor = StompHeaderAccessor.wrap(result);
        assertThat(accessor.getUser()).isNotNull();
        assertThat(accessor.getUser().getName()).isEqualTo("ada");
    }

    @Test
    @DisplayName("a connect with no token is refused")
    void missingTokenIsRefused() {
        assertThatThrownBy(() -> send(frame(StompCommand.CONNECT, null)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an invalid token is refused")
    void invalidTokenIsRefused() {
        when(jwtUtil.validateToken(anyString())).thenReturn(false);

        assertThatThrownBy(() -> send(frame(StompCommand.CONNECT, "Bearer nonsense")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a token without the Bearer scheme is refused")
    void nonBearerIsRefused() {
        assertThatThrownBy(() -> send(frame(StompCommand.CONNECT, VALID_TOKEN)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a well-formed token naming nobody is refused, not a 500")
    void deletedAccountIsRefused() {
        when(userDetailsService.loadUserByUsername("ada"))
            .thenThrow(new UsernameNotFoundException("gone"));

        assertThatThrownBy(() -> send(frame(StompCommand.CONNECT, "Bearer " + VALID_TOKEN)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("frames other than CONNECT pass through untouched")
    void otherFramesPassThrough() {
        // The session was authenticated at CONNECT; re-checking every SEND
        // would cost a user lookup per cursor movement.
        Message<?> message = frame(StompCommand.SEND, null);

        assertThat(send(message)).isSameAs(message);
    }
}
