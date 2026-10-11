package dev.dwij.chat.message.cache;

import dev.dwij.chat.common.RedisKeys;
import dev.dwij.chat.common.json.Json;
import dev.dwij.chat.common.model.ChatMessage;
import dev.dwij.chat.message.config.MessageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The last fifty messages of a conversation, in Redis.
 *
 * <p>Opening a chat shows the bottom of the thread, and almost nobody scrolls past it, so this handful of
 * messages absorbs the overwhelming majority of all reads and keeps them off the database entirely.
 *
 * <p>A sorted set scored by serverSeq rather than a list, for two reasons. Writes are idempotent — ZADD of
 * a message already present just rescores it, so a Kafka replay cannot produce the duplicate that LPUSH
 * would. And the set stays ordered by sequence no matter what order the writes arrive in, so a message
 * that overtakes another in flight still lands in the right place.
 */
@Component
public class RecentMessageCache {

    private static final Logger log = LoggerFactory.getLogger(RecentMessageCache.class);

    private final StringRedisTemplate redis;
    private final MessageProperties properties;

    public RecentMessageCache(StringRedisTemplate redis, MessageProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public void add(ChatMessage message) {
        String key = RedisKeys.recentMessages(message.getConversationId());
        try {
            redis.opsForZSet().add(key, Json.write(message), message.getServerSeq());
            // Trim from the low-score end, keeping the newest N. Done on every write so the entry can
            // never grow unbounded for a busy conversation.
            redis.opsForZSet().removeRange(key, 0, -(properties.getRecentCacheSize() + 1));
            redis.expire(key, properties.getRecentCacheTtl());
        } catch (RuntimeException e) {
            // The cache is an optimisation, never a source of truth. A Redis outage makes reads slower and
            // more expensive, not wrong, so a failure here must not fail the message.
            log.warn("Could not cache {}: {}", message.getMessageId(), e.toString());
        }
    }

    /**
     * @return the newest cached messages, newest first, or an empty list if the cache cannot answer the
     *         request in full. Returning nothing rather than a partial page keeps the caller's decision
     *         simple: if the cache does not have it, go to the store.
     */
    public List<ChatMessage> newest(String conversationId, int limit) {
        if (limit > properties.getRecentCacheSize()) {
            return List.of();
        }
        try {
            Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet()
                    .reverseRangeWithScores(RedisKeys.recentMessages(conversationId), 0, limit - 1);
            if (tuples == null || tuples.isEmpty()) {
                return List.of();
            }
            List<ChatMessage> messages = new ArrayList<>(tuples.size());
            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                if (tuple.getValue() != null) {
                    messages.add(Json.read(tuple.getValue(), ChatMessage.class));
                }
            }
            return messages;
        } catch (RuntimeException e) {
            log.warn("Cache read failed for {}: {}", conversationId, e.toString());
            return List.of();
        }
    }

    public long size(String conversationId) {
        Long size = redis.opsForZSet().zCard(RedisKeys.recentMessages(conversationId));
        return size == null ? 0 : size;
    }
}
