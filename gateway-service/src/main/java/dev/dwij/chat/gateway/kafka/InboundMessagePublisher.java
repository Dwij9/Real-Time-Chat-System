package dev.dwij.chat.gateway.kafka;

import dev.dwij.chat.common.Topics;
import dev.dwij.chat.common.json.Json;
import dev.dwij.chat.common.model.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Hands an accepted message to Kafka, keyed by conversation id.
 *
 * <p>The key is the reason a conversation reads back in the order it was written: all of its messages land
 * on one partition, and one consumer assigns sequence numbers for it. Keying by sender instead would let
 * two people's messages in the same chat be sequenced by two consumers racing each other.
 */
@Component
public class InboundMessagePublisher {

    private static final Logger log = LoggerFactory.getLogger(InboundMessagePublisher.class);

    private final KafkaTemplate<String, String> kafka;

    public InboundMessagePublisher(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    public void publish(ChatMessage message) {
        kafka.send(Topics.CHAT_MESSAGES, message.getConversationId(), Json.write(message))
                .whenComplete((result, error) -> {
                    if (error != null) {
                        // acks=all with retries is configured on the producer, so reaching here means the
                        // broker was unreachable for the whole retry budget. The client still holds the
                        // message in its outbox and will resend it with the same messageId.
                        log.error("Could not publish {}: {}", message.getMessageId(), error.toString());
                    }
                });
    }
}
