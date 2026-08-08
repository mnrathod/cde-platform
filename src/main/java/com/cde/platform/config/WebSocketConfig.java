package com.cde.platform.config;

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

    public WebSocketConfig(StompAuthChannelInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // No SockJS fallback: the browsers this targets all speak WebSocket,
        // and the fallback transports would need their own CORS and session
        // handling for no gain.
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
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
        // browser cannot attach an Authorization header to, so the token
        // arrives in the STOMP CONNECT frame instead.
        registration.interceptors(authInterceptor);
    }
}
