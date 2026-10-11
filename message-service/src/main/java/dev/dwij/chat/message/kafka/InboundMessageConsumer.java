package dev.dwij.chat.message.kafka;

import dev.dwij.chat.common.json.Json;
import dev.dwij.chat.common.model.ChatMessage;
import dev.dwij.chat.common.model.FanoutEvent;
import dev.dwij.chat.message.cache.RecentMessageCache;
import dev.dwij.chat.message.cache.SequenceAllocator;
import dev.dwij.chat.message.store.MemberDirectory;
import dev.dwij.chat.message.store.MessageStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The write path, in the order that matters: number it, store it, cache it, then tell people about it.
 *
 * <p>Storing before fanning out is deliberate. If this process dies midway, Kafka redelivers from the last
 * committed offset and the work is simply redone — the store upserts, the cache rescores, and the sequence
 * is reused from its marker, so a redo is indistinguishable from the original. The opposite order would
 * let a user receive a message that was never durably written, and a reload would make it vanish.
 *
 * <p>Offsets are committed by hand, after the work, for the same reason. Automatic commits would advance
 * the offset on a timer and could acknowledge a message that was never stored.
 */
@Component
public class InboundMessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(InboundMessageConsumer.class);

    private final MessageStore store;
    private final RecentMessageCache cache;
    private final SequenceAllocator sequences;
    private final MemberDirectory members;
    private final FanoutPublisher fanout;
    private final Counter stored;
    private final Counter replayed;
    private final Counter fannedOut;
    private final Timer writeLatency;

    public InboundMessageConsumer(MessageStore store,
                                  RecentMessageCache cache,
                                  SequenceAllocator sequences,
                                  MemberDirectory members,
                                  FanoutPublisher fanout,
                                  MeterRegistry meters) {
        this.store = store;
        this.cache = cache;
        this.sequences = sequences;
        this.members = members;
        this.fanout = fanout;
        this.stored = Counter.builder("chat.message.stored").register(meters);
        this.replayed = Counter.builder("chat.message.replayed").register(meters);
        this.fannedOut = Counter.builder("chat.message.fanout.events").register(meters);
        this.writeLatency = Timer.builder("chat.message.write.latency").register(meters);
    }

    @KafkaListener(
            topics = "#{T(dev.dwij.chat.common.Topics).CHAT_MESSAGES}",
            groupId = "message-service",
            concurrency = "${chat.message.consumer-concurrency:3}")
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        writeLatency.record(() -> process(record));
        ack.acknowledge();
    }

    private void process(ConsumerRecord<String, String> record) {
        ChatMessage message = Json.read(record.value(), ChatMessage.class);

        SequenceAllocator.Allocation allocation = sequences.allocate(message.getConversationId(), message.getMessageId());
        message.setServerSeq(allocation.sequence());
        message.setStoredAt(System.currentTimeMillis());
        if (!allocation.firstAttempt()) {
            replayed.increment();
            log.debug("Reprocessing {} at its original sequence {}", message.getMessageId(), allocation.sequence());
        }

        store.save(message);
        cache.add(message);
        stored.increment();

        List<String> recipients = members.members(message.getConversationId());
        if (recipients.isEmpty()) {
            log.warn("No members for conversation {}; message {} stored but not delivered",
                    message.getConversationId(), message.getMessageId());
            return;
        }
        for (String recipient : recipients) {
            // The sender's other devices are included on purpose: a message typed on a phone should appear
            // on the laptop too. notification-service suppresses the push for the sender.
            fanout.publish(new FanoutEvent(recipient, message, message.getSenderId(), null));
            fannedOut.increment();
        }
    }
}
