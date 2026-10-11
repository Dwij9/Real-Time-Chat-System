package dev.dwij.chat.gateway.delivery;

import dev.dwij.chat.common.json.Json;
import dev.dwij.chat.common.model.DeliveryEnvelope;
import dev.dwij.chat.common.model.Frames;
import dev.dwij.chat.gateway.ws.SessionRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Receives messages aimed at this node's sockets and writes them to the wire.
 *
 * <p>Redis pub/sub is used rather than Kafka for this last hop because it is a point-to-point handoff to
 * one named node with no need for replay: if the socket has already gone, the message is still safe in
 * Cosmos DB and the client will pick it up from history on reconnect.
 */
@Component
public class DeliverySubscriber implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(DeliverySubscriber.class);

    private final SessionRegistry sessions;
    private final Counter delivered;
    private final Counter missed;

    public DeliverySubscriber(SessionRegistry sessions, MeterRegistry meters) {
        this.sessions = sessions;
        this.delivered = Counter.builder("chat.gateway.delivery.sent").register(meters);
        this.missed = Counter.builder("chat.gateway.delivery.missed").register(meters);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            DeliveryEnvelope envelope = Json.read(new String(message.getBody(), StandardCharsets.UTF_8),
                    DeliveryEnvelope.class);
            int count = sessions.sendToUser(envelope.recipientId(), Frames.Outbound.message(envelope.message()));
            if (count > 0) {
                delivered.increment(count);
            } else {
                // The socket closed between the presence lookup and this hop. Not lost: the message is
                // already durable, and the client fetches it from history when it comes back.
                missed.increment();
            }
        } catch (RuntimeException e) {
            log.warn("Could not deliver envelope: {}", e.toString());
        }
    }
}
