package dev.dwij.chat.gateway.ws;

import dev.dwij.chat.common.json.Json;
import dev.dwij.chat.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The live sockets this node owns, keyed by user. One user can hold several at once (phone, laptop, a
 * second tab), and every one of them gets the message.
 *
 * <p>Each session is wrapped in a {@link ConcurrentWebSocketSessionDecorator}: a raw WebSocketSession is
 * not safe for concurrent sends, and here the delivery subscriber thread and the client's own request
 * thread can both write to the same socket. The decorator also gives a send buffer limit, so one phone on
 * a bad connection cannot pin a thread or grow the heap without bound.
 */
@Component
public class SessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

    private final Map<String, Set<WebSocketSession>> sessionsByUser = new ConcurrentHashMap<>();
    private final GatewayProperties properties;

    public SessionRegistry(GatewayProperties properties) {
        this.properties = properties;
    }

    public WebSocketSession register(String userId, WebSocketSession session) {
        WebSocketSession guarded = new ConcurrentWebSocketSessionDecorator(
                session, (int) properties.getSendTimeout().toMillis(), properties.getMaxFrameBytes());
        sessionsByUser.computeIfAbsent(userId, key -> ConcurrentHashMap.newKeySet()).add(guarded);
        return guarded;
    }

    /** @return true when that was the user's last socket on this node. */
    public boolean unregister(String userId, WebSocketSession session) {
        Set<WebSocketSession> sessions = sessionsByUser.get(userId);
        if (sessions == null) {
            return true;
        }
        sessions.removeIf(existing -> sameUnderlyingSession(existing, session));
        if (sessions.isEmpty()) {
            sessionsByUser.remove(userId, sessions);
            return true;
        }
        return false;
    }

    /** @return how many sockets the payload actually reached. */
    public int sendToUser(String userId, Object payload) {
        Set<WebSocketSession> sessions = sessionsByUser.get(userId);
        if (sessions == null || sessions.isEmpty()) {
            return 0;
        }
        TextMessage frame = new TextMessage(Json.write(payload));
        int delivered = 0;
        for (WebSocketSession session : sessions) {
            if (send(session, frame)) {
                delivered++;
            } else {
                sessions.remove(session);
            }
        }
        return delivered;
    }

    public boolean send(WebSocketSession session, Object payload) {
        return send(session, new TextMessage(Json.write(payload)));
    }

    private boolean send(WebSocketSession session, TextMessage frame) {
        if (!session.isOpen()) {
            return false;
        }
        try {
            session.sendMessage(frame);
            return true;
        } catch (IOException | IllegalStateException e) {
            // IllegalStateException is the decorator telling us the send buffer limit was blown.
            log.debug("Dropping session {}: {}", session.getId(), e.getMessage());
            closeQuietly(session);
            return false;
        }
    }

    public Set<String> connectedUsers() {
        return Collections.unmodifiableSet(sessionsByUser.keySet());
    }

    public int sessionCount() {
        return sessionsByUser.values().stream().mapToInt(Set::size).sum();
    }

    public int userCount() {
        return sessionsByUser.size();
    }

    private boolean sameUnderlyingSession(WebSocketSession registered, WebSocketSession candidate) {
        return registered == candidate || registered.getId().equals(candidate.getId());
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close();
        } catch (Exception ignored) {
            // already gone
        }
    }
}
