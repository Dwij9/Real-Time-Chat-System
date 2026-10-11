package dev.dwij.chat.gateway.ws;

import dev.dwij.chat.common.json.Json;
import dev.dwij.chat.common.model.ChatMessage;
import dev.dwij.chat.common.model.Frames;
import dev.dwij.chat.gateway.config.GatewayProperties;
import dev.dwij.chat.gateway.kafka.InboundMessagePublisher;
import dev.dwij.chat.gateway.presence.PresenceService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The edge of the system. This class does as little as possible on purpose: authenticate, validate, hand
 * the message to Kafka, acknowledge. Nothing here talks to the database, so a slow write never costs a
 * connection, and a gateway node can be restarted at any time without losing a message that was accepted.
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    /**
     * Spring hands this handler the raw session, but the registry holds a concurrency-guarded wrapper
     * around it. Writing to the raw one from here while the delivery thread writes to the wrapper would
     * interleave two frames on the same socket, so the wrapper is stashed and used for every send.
     */
    private static final String GUARDED_SESSION = "chat.guardedSession";

    private final SessionRegistry sessions;
    private final PresenceService presence;
    private final InboundMessagePublisher publisher;
    private final GatewayProperties properties;
    private final Map<String, RateLimiter> limiters = new ConcurrentHashMap<>();
    private final Counter accepted;
    private final Counter rejected;

    public ChatWebSocketHandler(SessionRegistry sessions,
                                PresenceService presence,
                                InboundMessagePublisher publisher,
                                GatewayProperties properties,
                                MeterRegistry meters) {
        this.sessions = sessions;
        this.presence = presence;
        this.publisher = publisher;
        this.properties = properties;
        this.accepted = Counter.builder("chat.gateway.messages.accepted").register(meters);
        this.rejected = Counter.builder("chat.gateway.messages.rejected").register(meters);
        meters.gauge("chat.gateway.sessions.open", sessions, SessionRegistry::sessionCount);
        meters.gauge("chat.gateway.users.connected", sessions, SessionRegistry::userCount);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession rawSession) {
        String userId = userId(rawSession);
        WebSocketSession session = sessions.register(userId, rawSession);
        rawSession.getAttributes().put(GUARDED_SESSION, session);
        limiters.put(session.getId(), new RateLimiter(properties.getSendRateLimitPerSecond()));
        presence.markOnline(userId);
        sessions.send(session, Frames.Outbound.connected(userId, properties.getNodeId()));
        log.debug("{} connected on {}", userId, properties.getNodeId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession rawSession, TextMessage frame) {
        WebSocketSession session = guarded(rawSession);
        String userId = userId(session);
        Frames.Inbound inbound;
        try {
            inbound = Json.read(frame.getPayload(), Frames.Inbound.class);
        } catch (IllegalArgumentException e) {
            sessions.send(session, Frames.Outbound.error("Unreadable frame"));
            return;
        }

        String type = inbound.type() == null ? "" : inbound.type();
        switch (type) {
            case Frames.HEARTBEAT -> {
                // The client's liveness proof. Refreshing here rather than on a timer means presence
                // reflects a working socket, not merely a process that is still running.
                presence.refresh(userId);
                sessions.send(session, Frames.Outbound.pong());
            }
            case Frames.SEND -> handleSend(session, userId, inbound);
            case Frames.READ_RECEIPT -> log.trace("{} read {} up to {}", userId, inbound.conversationId(), inbound.upToSeq());
            default -> sessions.send(session, Frames.Outbound.error("Unknown frame type: " + type));
        }
    }

    private void handleSend(WebSocketSession session, String userId, Frames.Inbound inbound) {
        if (inbound.conversationId() == null || inbound.conversationId().isBlank()) {
            rejected.increment();
            sessions.send(session, Frames.Outbound.error("conversationId is required"));
            return;
        }
        if (inbound.body() == null || inbound.body().isEmpty()) {
            rejected.increment();
            sessions.send(session, Frames.Outbound.error("body is required"));
            return;
        }
        RateLimiter limiter = limiters.get(session.getId());
        if (limiter != null && !limiter.tryAcquire()) {
            rejected.increment();
            sessions.send(session, Frames.Outbound.error("Sending too fast, slow down"));
            return;
        }

        ChatMessage message = new ChatMessage(
                inbound.messageId() == null ? UUID.randomUUID().toString() : inbound.messageId(),
                inbound.conversationId(),
                userId,
                inbound.messageType(),
                inbound.body(),
                inbound.sentAt() == null ? System.currentTimeMillis() : inbound.sentAt());

        // Acknowledged as queued, not as stored. The client keeps it in its outbox until the message
        // comes back through the delivery path with a server sequence on it.
        publisher.publish(message);
        accepted.increment();
        sessions.send(session, Frames.Outbound.queued(message.getMessageId(), message.getConversationId()));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String userId = userId(session);
        limiters.remove(session.getId());
        if (sessions.unregister(userId, session)) {
            // Last socket for this user on this node. The presence key is deleted only if it still points
            // here, so a user who has already reconnected elsewhere is not knocked offline by this close.
            presence.markOffline(userId);
        }
        log.debug("{} disconnected from {} ({})", userId, properties.getNodeId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("Transport error on {}: {}", session.getId(), exception.toString());
        try {
            session.close(CloseStatus.SERVER_ERROR);
        } catch (Exception ignored) {
            // already closing
        }
    }

    private WebSocketSession guarded(WebSocketSession session) {
        Object guarded = session.getAttributes().get(GUARDED_SESSION);
        return guarded instanceof WebSocketSession wrapped ? wrapped : session;
    }

    private String userId(WebSocketSession session) {
        Object userId = session.getAttributes().get(AuthHandshakeInterceptor.USER_ID);
        if (userId == null) {
            throw new IllegalStateException("Session reached the handler without an authenticated user");
        }
        return userId.toString();
    }
}
