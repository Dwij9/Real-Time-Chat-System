package dev.dwij.chat.common;

import dev.dwij.chat.common.security.JwtService;
import dev.dwij.chat.common.security.TokenException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtServiceTest {

    private final JwtService jwt = new JwtService("local-dev-secret-change-me", Duration.ofMinutes(30));

    @Test
    void roundTripsTheSubject() {
        assertEquals("alice", jwt.verify(jwt.issue("alice")));
    }

    @Test
    void rejectsATamperedPayload() {
        String token = jwt.issue("alice");
        String[] parts = token.split("\\.");
        String forged = parts[0] + ".eyJzdWIiOiJtYWxsb3J5IiwiZXhwIjo5OTk5OTk5OTk5fQ." + parts[2];
        assertThrows(TokenException.class, () -> jwt.verify(forged));
    }

    @Test
    void rejectsAnExpiredToken() {
        JwtService expired = new JwtService("local-dev-secret-change-me", Duration.ofSeconds(-1));
        assertThrows(TokenException.class, () -> expired.verify(expired.issue("alice")));
    }
}
