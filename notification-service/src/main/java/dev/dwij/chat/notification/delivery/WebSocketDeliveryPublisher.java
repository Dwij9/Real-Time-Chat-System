package dev.dwij.chat.notification.delivery;

import dev.dwij.chat.common.RedisKeys;
import dev.dwij.chat.common.json.Json;
import dev.dwij.chat.common.model.ChatMessage;
import dev.dwij.chat.common.model.DeliveryEnvelope;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Hands the message to the one gateway node holding the recipient's socket.
 *
 * <p>Addressed to a named node rather than broadcast, so adding gateway capacity does not multiply the
 * work every node has to discard.
 */
@Component
public class WebSocketDeliveryPublisher {

    private final StringRedisTemplate redis;

    public WebSocketDeliveryPublisher(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void publish(String nodeId, String recipientId, ChatMessage message) {
        redis.convertAndSend(RedisKeys.gatewayChannel(nodeId),
                Json.write(new DeliveryEnvelope(recipientId, message)));
    }
}
