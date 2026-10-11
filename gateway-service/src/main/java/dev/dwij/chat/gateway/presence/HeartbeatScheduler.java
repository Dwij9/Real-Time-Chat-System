package dev.dwij.chat.gateway.presence;

import dev.dwij.chat.common.RedisKeys;
import dev.dwij.chat.gateway.config.GatewayProperties;
import dev.dwij.chat.gateway.ws.SessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Server-side safety net for presence. Clients heartbeat too, but a mobile client that is backgrounded
 * mid-conversation can go quiet for a while with the socket still healthy. This refreshes every user this
 * node actually holds, in one pipelined round trip rather than one call per user.
 *
 * <p>At 50k sockets a node and a 10 second interval that is 5k key writes a second per node, which is why
 * it is pipelined and why presence lives on its own Redis cluster rather than sharing the cache.
 */
@Component
public class HeartbeatScheduler {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatScheduler.class);

    private final SessionRegistry sessions;
    private final StringRedisTemplate redis;
    private final GatewayProperties properties;

    public HeartbeatScheduler(SessionRegistry sessions, StringRedisTemplate redis, GatewayProperties properties) {
        this.sessions = sessions;
        this.redis = redis;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${chat.gateway.heartbeat-interval:10s}")
    public void refreshPresence() {
        Set<String> users = sessions.connectedUsers();
        if (users.isEmpty()) {
            return;
        }
        String nodeId = properties.getNodeId();
        long ttlSeconds = properties.getPresenceTtl().toSeconds();
        try {
            redis.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                for (String userId : users) {
                    connection.stringCommands().set(
                            RedisKeys.presence(userId).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            nodeId.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            org.springframework.data.redis.core.types.Expiration.seconds(ttlSeconds),
                            org.springframework.data.redis.connection.RedisStringCommands.SetOption.upsert());
                }
                return null;
            });
        } catch (RuntimeException e) {
            // Presence is best-effort. If Redis is briefly unavailable the keys expire, users look offline
            // and their messages fall back to push: degraded, not broken. Nothing here should kill the node.
            log.warn("Presence refresh failed for {} users: {}", users.size(), e.toString());
        }
    }
}
