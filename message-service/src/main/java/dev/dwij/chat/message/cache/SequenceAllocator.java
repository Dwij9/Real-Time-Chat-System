package dev.dwij.chat.message.cache;

import dev.dwij.chat.common.RedisKeys;
import dev.dwij.chat.common.model.ChatMessage;
import dev.dwij.chat.message.config.MessageProperties;
import dev.dwij.chat.message.store.MessageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Assigns each message its position in the conversation, and remembers the assignment.
 *
 * <p>Ordering cannot come from client timestamps — phone clocks drift, and two people typing at once would
 * tie. It comes from a Redis INCR per conversation, performed by the single consumer that owns that
 * conversation's Kafka partition, so there is never more than one allocator for a given chat.
 *
 * <p>The second half matters more than it looks. The allocated number is written under the message id, so
 * if the consumer dies after allocating but before finishing, the replay of that same message finds its
 * original number and reuses it. Without that, a replay would give an already-stored message a second,
 * higher sequence and it would appear twice in the thread at two different positions. Sequences are
 * monotonic but not gapless: a crashed attempt burns a number, which costs nothing because nothing in the
 * system assumes continuity.
 *
 * <p>Redis is a cache, not a ledger, so a counter can be lost. When one comes back as fresh, the
 * high-water mark is recovered from the durable store before use — otherwise new messages would be
 * numbered from 1 again and sort underneath the existing history.
 */
@Component
public class SequenceAllocator {

    private static final Logger log = LoggerFactory.getLogger(SequenceAllocator.class);

    public record Allocation(long sequence, boolean firstAttempt) {
    }

    private final StringRedisTemplate redis;
    private final MessageStore store;
    private final Duration markerTtl;

    public SequenceAllocator(StringRedisTemplate redis, MessageStore store, MessageProperties properties) {
        this.redis = redis;
        this.store = store;
        this.markerTtl = properties.getIdempotencyTtl();
    }

    public Allocation allocate(String conversationId, String messageId) {
        String markerKey = RedisKeys.assignedSequence(messageId);
        String alreadyAssigned = redis.opsForValue().get(markerKey);
        if (alreadyAssigned != null) {
            return new Allocation(Long.parseLong(alreadyAssigned), false);
        }

        String counterKey = RedisKeys.sequence(conversationId);
        Long next = redis.opsForValue().increment(counterKey);
        long sequence = next == null ? 1L : next;
        if (sequence == 1L) {
            sequence = recoverHighWaterMark(conversationId, counterKey);
        }

        Boolean claimed = redis.opsForValue().setIfAbsent(markerKey, String.valueOf(sequence), markerTtl);
        if (Boolean.FALSE.equals(claimed)) {
            // Lost a duplicate-delivery race with another thread. Its number wins; the one just allocated
            // is abandoned, leaving a harmless gap.
            String winner = redis.opsForValue().get(markerKey);
            if (winner != null) {
                return new Allocation(Long.parseLong(winner), false);
            }
        }
        return new Allocation(sequence, true);
    }

    /**
     * The counter was empty, which means either a genuinely new conversation or a lost Redis shard. Ask
     * the durable store for the newest sequence it holds and continue from there.
     */
    private long recoverHighWaterMark(String conversationId, String counterKey) {
        try {
            List<ChatMessage> newest = store.history(conversationId, null, 1);
            if (newest.isEmpty()) {
                return 1L;
            }
            long resumeAt = newest.get(0).getServerSeq() + 1;
            redis.opsForValue().set(counterKey, String.valueOf(resumeAt));
            log.info("Rebuilt sequence counter for {} at {}", conversationId, resumeAt);
            return resumeAt;
        } catch (RuntimeException e) {
            // Falling back to 1 would corrupt the ordering of an existing conversation, so fail the
            // message instead and let Kafka redeliver it.
            throw new IllegalStateException("Could not recover sequence for " + conversationId, e);
        }
    }
}
