package dev.dwij.chat.message.kafka;

import dev.dwij.chat.common.Topics;
import dev.dwij.chat.common.json.Json;
import dev.dwij.chat.common.model.FanoutEvent;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Turns one stored message into one event per recipient, keyed by recipient id.
 *
 * <p>Fanning out here rather than at the gateway is what makes group chat affordable: the message is
 * written once and the expansion happens after the durable write, so a 200-person group costs one database
 * write and 200 cheap events, not 200 writes.
 */
@Component
public class FanoutPublisher {

    private final KafkaTemplate<String, String> kafka;

    public FanoutPublisher(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    public void publish(FanoutEvent event) {
        kafka.send(Topics.CHAT_FANOUT, event.recipientId(), Json.write(event));
    }
}
