package dev.dwij.chat.gateway;

import dev.dwij.chat.gateway.ws.RateLimiter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    @Test
    void allowsABurstUpToCapacityThenRefuses() {
        RateLimiter limiter = new RateLimiter(5);
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire(), "burst permit " + i + " should be allowed");
        }
        assertFalse(limiter.tryAcquire(), "bucket is empty");
    }

    @Test
    void refillsOverTime() throws InterruptedException {
        RateLimiter limiter = new RateLimiter(10);
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire();
        }
        assertFalse(limiter.tryAcquire());
        Thread.sleep(250);
        assertTrue(limiter.tryAcquire(), "a quarter second buys about two permits back");
    }
}
