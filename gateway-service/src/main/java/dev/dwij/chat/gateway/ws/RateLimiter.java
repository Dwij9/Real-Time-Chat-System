package dev.dwij.chat.gateway.ws;

import java.util.concurrent.atomic.AtomicReference;

/**
 * A token bucket per connection. Small enough to hold one per socket at 50k sockets a node, and it keeps a
 * single runaway client from filling the Kafka partition that a whole conversation shares.
 */
public class RateLimiter {

    private record Bucket(double tokens, long lastRefillMillis) {
    }

    private final double capacity;
    private final double tokensPerMilli;
    private final AtomicReference<Bucket> bucket;

    public RateLimiter(int permitsPerSecond) {
        this.capacity = permitsPerSecond;
        this.tokensPerMilli = permitsPerSecond / 1000.0;
        this.bucket = new AtomicReference<>(new Bucket(permitsPerSecond, System.currentTimeMillis()));
    }

    public boolean tryAcquire() {
        long now = System.currentTimeMillis();
        while (true) {
            Bucket current = bucket.get();
            double refilled = Math.min(capacity,
                    current.tokens() + Math.max(0, now - current.lastRefillMillis()) * tokensPerMilli);
            if (refilled < 1.0) {
                return false;
            }
            if (bucket.compareAndSet(current, new Bucket(refilled - 1.0, now))) {
                return true;
            }
        }
    }
}
