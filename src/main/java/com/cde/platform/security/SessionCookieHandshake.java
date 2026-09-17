package com.cde.platform.security;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * Carries the session cookie from the WebSocket handshake into the STOMP
 * session.
 *
 * <p>The handshake is an ordinary HTTP GET, so the browser attaches cookies to
 * it exactly as it would to any other same-site request. It cannot attach an
 * {@code Authorization} header — that is why the token used to travel in the
 * CONNECT frame instead — but now that the web client holds no token at all
 * (§4.6, {@link SessionCookie}), the cookie on the handshake is the only
 * credential a browser has to offer.
 *
 * <p>This only lifts the value across. {@link StompAuthChannelInterceptor}
 * still decides whether it is valid and still refuses a CONNECT that resolves
 * to nobody, so an unauthenticated socket cannot subscribe to anything.
 *
 * <p><strong>Why the endpoint's origin list had to be narrowed at the same
 * time.</strong> A wildcard was safe while the only credential was a bearer
 * token, because a page on another origin has no way to obtain one. A cookie
 * is different: the browser sends it on a cross-site WebSocket handshake
 * without being asked, and {@code SameSite} does not cover the WebSocket
 * handshake in every browser. A wildcard plus cookie authentication is
 * cross-site WebSocket hijacking — any page anyone visits could open a socket
 * as them and read their collaboration traffic. {@code WebSocketConfig} now
 * names the origins.
 */
@Component
public class SessionCookieHandshake implements HandshakeInterceptor {

    /** Where the lifted token is left for the channel interceptor to find. */
    public static final String TOKEN_ATTRIBUTE = "cde.session.token";

    private final SessionCookie sessionCookie;

    public SessionCookieHandshake(SessionCookie sessionCookie) {
        this.sessionCookie = sessionCookie;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            sessionCookie.readFrom(servletRequest.getServletRequest())
                .ifPresent(token -> attributes.put(TOKEN_ATTRIBUTE, token));
        }
        // Always allowed through. A handshake without a usable cookie is not
        // refused here, because a mobile client legitimately arrives that way
        // and authenticates on the CONNECT frame a moment later. Refusing now
        // would reject the clients this is not for.
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler handler, Exception exception) {
        // Nothing to undo: the attribute lives on the WebSocket session, which
        // ends with the socket.
    }
}
