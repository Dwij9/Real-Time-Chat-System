package dev.dwij.chat.notification.kafka;

import dev.dwij.chat.common.json.Json;
import dev.dwij.chat.common.model.FanoutEvent;
import dev.dwij.chat.notification.delivery.WebSocketDeliveryPublisher;
import dev.dwij.chat.notification.presence.PresenceLookup;
import dev.dwij.chat.notification.push.PushService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * One decision, taken once per recipient: push it down a live socket, or wake their phone.
 *
 * <p>Presence is what decides, and presence is a Redis key with a 30 second TTL. The failure mode is
 * benign in both directions. If someone has just gone offline and still looks present, the message goes to
 * a gateway that no longer holds the socket and is dropped there — the message is already stored, so the
 * client sees it on reconnect. If someone has just come online and still looks absent, they get a push for
 * a message their open app is about to show, which is a wasted notification rather than a lost message.
 */
@Component
public class FanoutConsumer {

    private static final Logger log = LoggerFactory.getLogger(FanoutConsumer.class);

    private final PresenceLookup presence;
    private final WebSocketDeliveryPublisher websocket;
    private final PushService push;
    private final Counter deliveredLive;
    private final Counter pushed;

    public FanoutConsumer(PresenceLookup presence,
                          WebSocketDeliveryPublisher websocket,
                          PushService push,
                          MeterRegistry meters) {
        this.presence = presence;
        this.websocket = websocket;
        this.push = push;
        this.deliveredLive = Counter.builder("chat.notification.websocket").register(meters);
        this.pushed = Counter.builder("chat.notification.push").register(meters);
    }

    @KafkaListener(
            topics = "#{T(dev.dwij.chat.common.Topics).CHAT_FANOUT}",
            groupId = "notification-service",
            concurrency = "${chat.notification.consumer-concurrency:6}")
    public void onFanout(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            FanoutEvent event = Json.read(record.value(), FanoutEvent.class);
            Optional<String> gateway = presence.gatewayFor(event.recipientId());

            if (gateway.isPresent()) {
                websocket.publish(gateway.get(), event.recipientId(), event.message());
                deliveredLive.increment();
            } else if (!event.recipientId().equals(event.message().getSenderId())) {
                // No push to the sender's own account: their other devices get the socket copy, and
                // nobody wants their phone buzzing for something they just typed.
                push.notifyOffline(event.recipientId(), event.message(),
                        event.senderDisplayName(), event.conversationTitle());
                pushed.increment();
            }
        } catch (RuntimeException e) {
            // Delivery is best-effort by design, so a bad event is logged and dropped rather than retried
            // forever. Durability was settled upstream, before this topic was written.
            log.warn("Dropping unprocessable fanout event at offset {}: {}", record.offset(), e.toString());
        } finally {
            ack.acknowledge();
        }
    }
}
