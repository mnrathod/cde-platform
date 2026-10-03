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

    private JwtTokenService            jwtTokenService;
    private UserDetailsService userDetailsService;
    private StompAuthChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        jwtTokenService            = mock(JwtTokenService.class);
        userDetailsService = mock(UserDetailsService.class);
        interceptor        = new StompAuthChannelInterceptor(jwtTokenService, userDetailsService);

        when(jwtTokenService.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwtTokenService.extractUsername(VALID_TOKEN)).thenReturn("ada");
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
        when(jwtTokenService.isTokenValid(anyString())).thenReturn(false);

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

    // ── The two places a credential can come from ─────────────────────────

    /** A CONNECT frame whose handshake left a session cookie behind. */
    private Message<?> frameWithHandshakeToken(Object token) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        java.util.Map<String, Object> session = new java.util.HashMap<>();
        if (token != null) session.put(SessionCookieHandshake.TOKEN_ATTRIBUTE, token);
        accessor.setSessionAttributes(session);
        accessor.setLeaveMutable(true);
        return org.springframework.messaging.support.MessageBuilder
            .createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    @DisplayName("a browser's session cookie from the handshake authenticates the socket")
    void handshakeCookieAuthenticates() {
        // The browser path. A browser cannot set an Authorization header on a
        // WebSocket handshake, and since the session moved into an HttpOnly
        // cookie the web client holds no token to send on CONNECT either — so
        // without this there is no way for it to connect at all.
        assertThat(send(frameWithHandshakeToken(VALID_TOKEN))).isNotNull();
    }

    @Test
    @DisplayName("a session with no cookie on it is refused")
    void handshakeWithoutACookieIsRefused() {
        assertThatThrownBy(() -> send(frameWithHandshakeToken(null)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a blank cookie value is refused rather than treated as a token")
    void blankHandshakeCookieIsRefused() {
        assertThatThrownBy(() -> send(frameWithHandshakeToken("   ")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a cookie attribute that is not a string is refused")
    void nonStringHandshakeCookieIsRefused() {
        // The attribute map is untyped, so something else landing under that
        // key must not be handed to the token parser.
        assertThatThrownBy(() -> send(frameWithHandshakeToken(42)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a CONNECT naming a token wins over the handshake's cookie")
    void connectFrameWinsOverTheCookie() {
        // Matching JwtFilter: a client that went to the trouble of naming an
        // identity should not be overruled by an ambient one.
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer " + VALID_TOKEN);
        java.util.Map<String, Object> session = new java.util.HashMap<>();
        session.put(SessionCookieHandshake.TOKEN_ATTRIBUTE, "a.different.token");
        accessor.setSessionAttributes(session);
        accessor.setLeaveMutable(true);

        assertThat(send(org.springframework.messaging.support.MessageBuilder
            .createMessage(new byte[0], accessor.getMessageHeaders()))).isNotNull();
    }

    @Test
    @DisplayName("an empty Authorization header list falls through to the handshake")
    void emptyHeaderListFallsThrough() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", null);
        java.util.Map<String, Object> session = new java.util.HashMap<>();
        session.put(SessionCookieHandshake.TOKEN_ATTRIBUTE, VALID_TOKEN);
        accessor.setSessionAttributes(session);
        accessor.setLeaveMutable(true);

        assertThat(send(org.springframework.messaging.support.MessageBuilder
            .createMessage(new byte[0], accessor.getMessageHeaders()))).isNotNull();
    }

    // ── Recording the tenant for later frames ─────────────────────────────

    @Test
    @DisplayName("the connecting session's tenant is recorded for later frames")
    void recordsTheTenant() {
        // The destinations later frames name carry a document id and nothing
        // else, so without this there is no way to tell one tenant's document
        // 41 from another's — which was a cross-tenant leak until
        // CollaborationDestinationAuthorisation started reading it.
        when(jwtTokenService.extractTenantId(VALID_TOKEN))
            .thenReturn(java.util.Optional.of(7L));
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer " + VALID_TOKEN);
        java.util.Map<String, Object> session = new java.util.HashMap<>();
        accessor.setSessionAttributes(session);
        accessor.setLeaveMutable(true);

        send(org.springframework.messaging.support.MessageBuilder
            .createMessage(new byte[0], accessor.getMessageHeaders()));

        assertThat(session).containsEntry(
            StompAuthChannelInterceptor.TENANT_ATTRIBUTE, 7L);
    }

    @Test
    @DisplayName("a token naming no tenant records nothing, so later frames are refused")
    void recordsNothingWithoutATenantClaim() {
        // Fail closed. Recording a default, or leaving the attribute absent and
        // reading that as permission, is how the leak existed.
        when(jwtTokenService.extractTenantId(VALID_TOKEN)).thenReturn(java.util.Optional.empty());
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer " + VALID_TOKEN);
        java.util.Map<String, Object> session = new java.util.HashMap<>();
        accessor.setSessionAttributes(session);
        accessor.setLeaveMutable(true);

        send(org.springframework.messaging.support.MessageBuilder
            .createMessage(new byte[0], accessor.getMessageHeaders()));

        assertThat(session).doesNotContainKey(StompAuthChannelInterceptor.TENANT_ATTRIBUTE);
    }

    @Test
    @DisplayName("a session with no attribute map at all still connects")
    void connectsWithoutASessionMap() {
        // Nothing to record the tenant on, which is survivable: the
        // destination check refuses a session it cannot place, so the socket
        // connects and reaches no document.
        when(jwtTokenService.extractTenantId(VALID_TOKEN))
            .thenReturn(java.util.Optional.of(7L));

        assertThat(send(frame(StompCommand.CONNECT, "Bearer " + VALID_TOKEN))).isNotNull();
    }
}
