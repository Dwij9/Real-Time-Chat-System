package dev.dwij.chat.gateway.presence;

import dev.dwij.chat.common.RedisKeys;
import dev.dwij.chat.gateway.config.GatewayProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Online status, expressed as a key that expires.
 *
 * <p>presence:{userId} holds the id of the gateway node that owns the socket and carries a 30 second TTL.
 * The node refreshes it while the connection is alive. If the node is killed, loses its network, or is
 * drained by a deploy, nothing refreshes the key and Redis expires it on its own within 30 seconds. There
 * is no reaper job to run, no cleanup queue to drain, and no partially-cleaned state to reconcile after a
 * crash, because the absence of a heartbeat is itself the signal.
 *
 * <p>The cost of that simplicity is a window: for up to 30 seconds after a node dies its users still look
 * online, so their messages take the WebSocket path, find no socket, and are dropped rather than pushed.
 * Clients reconnect well inside that window, and a shorter TTL would trade it for a proportionally higher
 * write rate against the presence cluster. 30s and a 10s refresh is the point that was picked.
 */
@Service
public class PresenceService {

    /**
     * Delete the presence key only if it still names this node. Without the compare, a user who has
     * already reconnected to another gateway would be marked offline by the old node's close handler.
     */
    private static final RedisScript<Long> DELETE_IF_OWNED = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    /**
     * Refresh the TTL only if this node still owns the key, and re-create it if it has already expired
     * while the socket stayed up. One round trip either way.
     */
    private static final RedisScript<Long> REFRESH_IF_OWNED = new DefaultRedisScript<>(
            "local owner = redis.call('get', KEYS[1]) "
                    + "if owner == false or owner == ARGV[1] then "
                    + "  redis.call('set', KEYS[1], ARGV[1], 'EX', ARGV[2]) return 1 "
                    + "else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final GatewayProperties properties;

    public PresenceService(StringRedisTemplate redis, GatewayProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public void markOnline(String userId) {
        redis.opsForValue().set(RedisKeys.presence(userId), properties.getNodeId(), properties.getPresenceTtl());
    }

    public void refresh(String userId) {
        redis.execute(REFRESH_IF_OWNED, List.of(RedisKeys.presence(userId)),
                properties.getNodeId(), String.valueOf(properties.getPresenceTtl().toSeconds()));
    }

    public void markOffline(String userId) {
        redis.execute(DELETE_IF_OWNED, List.of(RedisKeys.presence(userId)), properties.getNodeId());
    }

    public String nodeFor(String userId) {
        return redis.opsForValue().get(RedisKeys.presence(userId));
    }
}
