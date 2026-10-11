package dev.dwij.chat.gateway.config;

import dev.dwij.chat.gateway.ws.AuthHandshakeInterceptor;
import dev.dwij.chat.gateway.ws.ChatWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler handler;
    private final AuthHandshakeInterceptor authInterceptor;
    private final GatewayProperties properties;

    public WebSocketConfig(ChatWebSocketHandler handler,
                           AuthHandshakeInterceptor authInterceptor,
                           GatewayProperties properties) {
        this.handler = handler;
        this.authInterceptor = authInterceptor;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws")
                .addInterceptors(authInterceptor)
                .setAllowedOriginPatterns("*");
    }

    /**
     * Idle timeout is longer than the client heartbeat interval but short enough that a laptop that
     * closed its lid without a TCP FIN does not hold a socket open forever.
     */
    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(properties.getMaxFrameBytes());
        container.setMaxBinaryMessageBufferSize(properties.getMaxFrameBytes());
        container.setMaxSessionIdleTimeout(90_000L);
        return container;
    }
}
