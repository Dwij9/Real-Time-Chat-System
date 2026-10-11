package dev.dwij.chat.gateway;

import dev.dwij.chat.gateway.config.GatewayProperties;
import dev.dwij.chat.gateway.ws.SessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionRegistryTest {

    private SessionRegistry registry;
    private final AtomicInteger sessionIds = new AtomicInteger();

    @BeforeEach
    void setUp() {
        registry = new SessionRegistry(new GatewayProperties());
    }

    @Test
    void deliversToEveryDeviceAUserHasOpen() {
        List<String> phone = new ArrayList<>();
        List<String> laptop = new ArrayList<>();
        registry.register("alice", openSession(phone));
        registry.register("alice", openSession(laptop));

        int delivered = registry.sendToUser("alice", java.util.Map.of("type", "message"));

        assertEquals(2, delivered);
        assertEquals(1, phone.size());
        assertEquals(1, laptop.size());
    }

    @Test
    void reportsTheUserGoneOnlyAfterTheLastSocketCloses() {
        WebSocketSession phone = openSession(new ArrayList<>());
        WebSocketSession laptop = openSession(new ArrayList<>());
        registry.register("alice", phone);
        registry.register("alice", laptop);

        assertFalse(registry.unregister("alice", phone), "laptop is still connected");
        assertTrue(registry.unregister("alice", laptop), "that was the last socket");
        assertEquals(0, registry.userCount());
    }

    @Test
    void sendingToAnUnknownUserIsASilentNoOp() {
        assertEquals(0, registry.sendToUser("nobody", java.util.Map.of("type", "message")));
    }

    private WebSocketSession openSession(List<String> sink) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s" + sessionIds.incrementAndGet());
        when(session.isOpen()).thenReturn(true);
        try {
            doAnswer(invocation -> {
                sink.add(((TextMessage) invocation.getArgument(0)).getPayload());
                return null;
            }).when(session).sendMessage(any());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return session;
    }
}
