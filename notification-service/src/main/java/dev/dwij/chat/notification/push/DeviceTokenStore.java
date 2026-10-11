package dev.dwij.chat.notification.push;

import dev.dwij.chat.common.RedisKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Device tokens, held as "platform|token" entries per user.
 *
 * <p>Tokens are removed when the platform reports them unregistered, which is the only reliable signal
 * that an app was uninstalled. Left alone, dead tokens accumulate and eventually dominate the push bill.
 */
@Component
public class DeviceTokenStore {

    public record Device(String platform, String token) {
    }

    private final StringRedisTemplate redis;

    public DeviceTokenStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void register(String userId, String platform, String token) {
        redis.opsForSet().add(RedisKeys.deviceTokens(userId), platform + "|" + token);
    }

    public void unregister(String userId, String platform, String token) {
        redis.opsForSet().remove(RedisKeys.deviceTokens(userId), platform + "|" + token);
    }

    public List<Device> devices(String userId) {
        Set<String> raw = redis.opsForSet().members(RedisKeys.deviceTokens(userId));
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return raw.stream()
                .map(entry -> entry.split("\\|", 2))
                .filter(parts -> parts.length == 2)
                .map(parts -> new Device(parts[0], parts[1]))
                .toList();
    }

    public long incrementUnread(String userId, String conversationId) {
        Long count = redis.opsForValue().increment(RedisKeys.unread(userId, conversationId));
        return count == null ? 1 : count;
    }

    public void clearUnread(String userId, String conversationId) {
        redis.delete(RedisKeys.unread(userId, conversationId));
    }
}
