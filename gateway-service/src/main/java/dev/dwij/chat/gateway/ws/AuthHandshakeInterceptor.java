package dev.dwij.chat.gateway.ws;

import dev.dwij.chat.common.security.JwtService;
import dev.dwij.chat.common.security.TokenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.UUID;

/**
 * Resolves the user at handshake time, once, before the socket exists. Every frame that arrives later is
 * attributed to that user id; a sender id in the frame body is ignored, so a client cannot speak as
 * someone else by editing its payload.
 */
@Component
public class AuthHandshakeInterceptor implements HandshakeInterceptor {

    static final String USER_ID = "userId";
    static final String DEVICE_ID = "deviceId";

    private static final Logger log = LoggerFactory.getLogger(AuthHandshakeInterceptor.class);

    private final JwtService jwtService;

    public AuthHandshakeInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = readToken(request);
        try {
            attributes.put(USER_ID, jwtService.verify(token));
        } catch (TokenException e) {
            log.debug("Rejected handshake: {}", e.getMessage());
            response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
            return false;
        }
        String deviceId = parameter(request, "deviceId");
        attributes.put(DEVICE_ID, deviceId == null ? UUID.randomUUID().toString() : deviceId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // nothing to do
    }

    private String readToken(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        // Browsers cannot set headers on a WebSocket handshake, so the query parameter is the practical path.
        return parameter(request, "token");
    }

    private String parameter(ServerHttpRequest request, String name) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            return servletRequest.getServletRequest().getParameter(name);
        }
        return null;
    }
}
