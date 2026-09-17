package com.cde.platform.config;

import com.cde.platform.security.SessionCookieHandshake;
import com.cde.platform.security.StompAuthChannelInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over WebSocket, carrying the live collaboration traffic: presence,
 * cursors and annotation changes.
 *
 * <p>STOMP rather than raw WebSocket frames because subscription routing,
 * heartbeats and per-destination fan-out are exactly what a message protocol
 * is for; hand-rolling a session registry and a topic router on top of
 * {@code TextWebSocketHandler} would be reimplementing this badly.
 *
 * <p><strong>Scaling.</strong> The simple broker keeps subscriptions in
 * memory, so two application replicas would each broadcast only to their own
 * clients. Running more than one instance needs
 * {@code enableStompBrokerRelay} pointed at RabbitMQ or ActiveMQ instead —
 * a configuration change here, not an application change, because everything
 * else publishes through {@link org.springframework.messaging.simp.SimpMessagingTemplate}.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor authInterceptor;
    private final SessionCookieHandshake sessionCookieHandshake;
    private final WebSecurityHeadersProperties webProperties;

    public WebSocketConfig(StompAuthChannelInterceptor authInterceptor,
                           SessionCookieHandshake sessionCookieHandshake,
                           WebSecurityHeadersProperties webProperties) {
        this.authInterceptor = authInterceptor;
        this.sessionCookieHandshake = sessionCookieHandshake;
        this.webProperties = webProperties;
    }

    /**
     * Registers the endpoint, and says who may open a socket to it.
     *
     * <p>This used to allow any origin, which was safe for as long as the only
     * credential was a bearer token on the CONNECT frame: a page on another
     * origin cannot obtain one. That stopped being true when the browser's
     * session moved into a cookie (§4.6). A browser attaches cookies to a
     * cross-site WebSocket handshake without being asked, and {@code SameSite}
     * does not reliably cover the handshake, so a wildcard plus cookie
     * authentication is cross-site WebSocket hijacking — any page a user
     * visits could open a socket as them and read a project's collaboration
     * traffic.
     *
     * <p>With no cross-origin callers configured, no pattern is registered at
     * all, which leaves same-origin only. That is the correct default: the
     * browser application is served from this image (ADR 15), so the ordinary
     * deployment needs nothing else.
     *
     * <p>No SockJS fallback — the browsers this targets all speak WebSocket,
     * and the fallback transports would need their own CORS and session
     * handling for no gain.
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        var endpoint = registry.addEndpoint("/ws")
            .addInterceptors(sessionCookieHandshake);

        if (webProperties.hasCrossOriginCallers()) {
            endpoint.setAllowedOrigins(
                webProperties.getAllowedOrigins().toArray(String[]::new));
        }
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // Authentication has to happen on the message channel, not in a
        // servlet filter: the WebSocket handshake is a single GET that the
        // browser cannot attach an Authorization header to. A native client
        // sends its token on the CONNECT frame; a browser's arrives as the
        // session cookie on the handshake, which SessionCookieHandshake
        // carries across for the interceptor to check.
        registration.interceptors(authInterceptor);
    }
}
