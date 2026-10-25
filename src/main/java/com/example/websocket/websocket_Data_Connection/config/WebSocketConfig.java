package com.example.websocket.websocket_Data_Connection.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * The server side of the app's WebSockets: browsers connect to {@code /ws} and subscribe
 * to {@code /topic/trades/<symbol>}.
 *
 * <p>STOMP is used on top of the raw WebSocket so that clients subscribe to named topics
 * and the server can publish with one call ({@code SimpMessagingTemplate.convertAndSend})
 * instead of tracking sessions by hand. For a feed that only flows server → browser,
 * Server-Sent Events would also work; WebSocket was chosen because this project is about
 * learning it, and it leaves room for the browser to send messages later.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    public static final String TRADES_TOPIC_PREFIX = "/topic/trades/";

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // In-memory broker: fine for one server. Several servers would need an external
        // broker (e.g. RabbitMQ) so that every browser gets every message.
        registry.enableSimpleBroker("/topic");
    }
}
