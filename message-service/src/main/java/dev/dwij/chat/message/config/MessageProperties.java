package dev.dwij.chat.message.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "chat.message")
public class MessageProperties {

    /** How many messages per conversation the Redis cache holds. Nearly every read is served from here. */
    private int recentCacheSize = 50;

    /** Idle lifetime of a conversation's cache entry. A chat nobody opens for a day falls out. */
    private Duration recentCacheTtl = Duration.ofHours(24);

    /** How long the messageId -> serverSeq marker survives, bounding replay-window deduplication. */
    private Duration idempotencyTtl = Duration.ofHours(24);

    private int maxGroupSize = 250;

    private int maxBodyLength = 8_000;

    private String jwtSecret = "local-dev-secret-change-me";

    public int getRecentCacheSize() {
        return recentCacheSize;
    }

    public void setRecentCacheSize(int recentCacheSize) {
        this.recentCacheSize = recentCacheSize;
    }

    public Duration getRecentCacheTtl() {
        return recentCacheTtl;
    }

    public void setRecentCacheTtl(Duration recentCacheTtl) {
        this.recentCacheTtl = recentCacheTtl;
    }

    public Duration getIdempotencyTtl() {
        return idempotencyTtl;
    }

    public void setIdempotencyTtl(Duration idempotencyTtl) {
        this.idempotencyTtl = idempotencyTtl;
    }

    public int getMaxGroupSize() {
        return maxGroupSize;
    }

    public void setMaxGroupSize(int maxGroupSize) {
        this.maxGroupSize = maxGroupSize;
    }

    public int getMaxBodyLength() {
        return maxBodyLength;
    }

    public void setMaxBodyLength(int maxBodyLength) {
        this.maxBodyLength = maxBodyLength;
    }

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }
}
