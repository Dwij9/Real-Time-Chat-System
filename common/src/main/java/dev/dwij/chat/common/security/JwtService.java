package dev.dwij.chat.common.security;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dwij.chat.common.json.Json;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * Minimal HS256 token issuing and verification.
 *
 * <p>Deliberately hand-rolled and dependency-free: in a real deployment the gateway would validate tokens
 * minted by the company IdP, and this class would be replaced by that library. What matters to the design
 * is that the gateway resolves a user id at handshake time and never trusts a user id sent in a frame.
 */
public class JwtService {

    private static final String HEADER = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] secret;
    private final Duration ttl;

    public JwtService(String secret, Duration ttl) {
        if (secret == null || secret.length() < 16) {
            throw new IllegalArgumentException("Signing secret must be at least 16 characters");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttl = ttl;
    }

    public String issue(String userId) {
        Instant now = Instant.now();
        String payload = Json.write(Map.of(
                "sub", userId,
                "iat", now.getEpochSecond(),
                "exp", now.plus(ttl).getEpochSecond()));
        String body = HEADER + "." + base64Url(payload.getBytes(StandardCharsets.UTF_8));
        return body + "." + base64Url(sign(body));
    }

    /** @return the user id carried by the token. */
    public String verify(String token) {
        if (token == null || token.isBlank()) {
            throw new TokenException("Missing token");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new TokenException("Malformed token");
        }
        String body = parts[0] + "." + parts[1];
        byte[] expected = sign(body);
        byte[] presented;
        try {
            presented = DECODER.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new TokenException("Malformed signature");
        }
        if (!MessageDigest.isEqual(expected, presented)) {
            throw new TokenException("Bad signature");
        }
        JsonNode claims;
        try {
            claims = Json.mapper().readTree(new String(DECODER.decode(parts[1]), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new TokenException("Unreadable claims");
        }
        if (!claims.hasNonNull("sub")) {
            throw new TokenException("No subject claim");
        }
        if (claims.path("exp").asLong(0) < Instant.now().getEpochSecond()) {
            throw new TokenException("Token expired");
        }
        return claims.get("sub").asText();
    }

    private byte[] sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    private static String base64Url(byte[] raw) {
        return ENCODER.encodeToString(raw);
    }
}
