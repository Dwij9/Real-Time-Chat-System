package dev.dwij.chat.message.store;

import dev.dwij.chat.common.RedisKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Membership lookup, cached in Redis.
 *
 * <p>Every single message needs this list to know who to fan out to, so at peak it is read 70k times a
 * second. Membership barely ever changes, which makes it the cheapest possible thing to cache: a Redis set
 * read instead of a database point read, with the database touched only on a miss or a membership change.
 */
@Component
public class MemberDirectory {

    private static final Duration TTL = Duration.ofHours(6);

    private final StringRedisTemplate redis;
    private final ConversationStore conversations;

    public MemberDirectory(StringRedisTemplate redis, ConversationStore conversations) {
        this.redis = redis;
        this.conversations = conversations;
    }

    public List<String> members(String conversationId) {
        String key = RedisKeys.members(conversationId);
        Set<String> cached = redis.opsForSet().members(key);
        if (cached != null && !cached.isEmpty()) {
            return new ArrayList<>(cached);
        }
        List<String> members = conversations.members(conversationId);
        if (!members.isEmpty()) {
            redis.opsForSet().add(key, members.toArray(String[]::new));
            redis.expire(key, TTL);
        }
        return members;
    }

    public void invalidate(String conversationId) {
        redis.delete(RedisKeys.members(conversationId));
    }
}
