package dev.dwij.chat.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.UUID;

@ConfigurationProperties(prefix = "chat.gateway")
public class GatewayProperties {

    /**
     * Identity of this gateway process. Presence records point at it, and notification-service publishes
     * to gw:{nodeId} to reach the sockets it owns. In Kubernetes this is set from the pod name.
     */
    private String nodeId = "gw-" + UUID.randomUUID().toString().substring(0, 8);

    /** How long a presence record survives without a heartbeat. */
    private Duration presenceTtl = Duration.ofSeconds(30);

    /** How often this node refreshes presence for the users it holds. Must be well under presenceTtl. */
    private Duration heartbeatInterval = Duration.ofSeconds(10);

    /** Largest frame accepted from a client. */
    private int maxFrameBytes = 64 * 1024;

    /** How long a slow client may back up before its socket is dropped. */
    private Duration sendTimeout = Duration.ofSeconds(5);

    /** Per-connection send rate ceiling, messages per second. */
    private int sendRateLimitPerSecond = 20;

    private String jwtSecret = "local-dev-secret-change-me";

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public Duration getPresenceTtl() {
        return presenceTtl;
    }

    public void setPresenceTtl(Duration presenceTtl) {
        this.presenceTtl = presenceTtl;
    }

    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(Duration heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

    public int getMaxFrameBytes() {
        return maxFrameBytes;
    }

    public void setMaxFrameBytes(int maxFrameBytes) {
        this.maxFrameBytes = maxFrameBytes;
    }

    public Duration getSendTimeout() {
        return sendTimeout;
    }

    public void setSendTimeout(Duration sendTimeout) {
        this.sendTimeout = sendTimeout;
    }

    public int getSendRateLimitPerSecond() {
        return sendRateLimitPerSecond;
    }

    public void setSendRateLimitPerSecond(int sendRateLimitPerSecond) {
        this.sendRateLimitPerSecond = sendRateLimitPerSecond;
    }

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }
}
