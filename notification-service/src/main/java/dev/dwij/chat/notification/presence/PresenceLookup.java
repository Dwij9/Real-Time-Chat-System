package dev.dwij.chat.notification.presence;

import dev.dwij.chat.common.RedisKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Answers the only question this service needs to ask: is this person connected right now, and to which
 * gateway? A missing key means offline, because presence keys are kept alive by heartbeats and nothing
 * else — no reaper job has to have run for the answer to be correct.
 */
@Component
public class PresenceLookup {

    private final StringRedisTemplate redis;

    public PresenceLookup(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Optional<String> gatewayFor(String userId) {
        return Optional.ofNullable(redis.opsForValue().get(RedisKeys.presence(userId)));
    }
}
