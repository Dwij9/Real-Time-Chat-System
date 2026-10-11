package dev.dwij.chat.notification.push;

import dev.dwij.chat.common.json.Json;
import dev.dwij.chat.notification.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Apple Push Notification service, over HTTP/2.
 *
 * <p>The connection is held open and reused across notifications: APNs rewards long-lived HTTP/2 streams
 * and penalises connection churn, and at this volume reconnecting per push would dominate the latency.
 *
 * <p>Authentication is left as configuration. A production deployment signs a JWT with the team's APNs key
 * and refreshes it every 40 minutes or so; that key handling belongs in a secret manager rather than here.
 */
@Component
@ConditionalOnProperty(name = "chat.notification.push-provider", havingValue = "apns")
public class ApnsPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(ApnsPushSender.class);

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final NotificationProperties properties;
    private final DeviceTokenStore tokens;
    private final String endpoint;
    private final String authToken;

    public ApnsPushSender(NotificationProperties properties,
                          DeviceTokenStore tokens,
                          @org.springframework.beans.factory.annotation.Value("${chat.notification.apns-endpoint:https://api.push.apple.com}") String endpoint,
                          @org.springframework.beans.factory.annotation.Value("${chat.notification.apns-auth-token:}") String authToken) {
        this.properties = properties;
        this.tokens = tokens;
        this.endpoint = endpoint;
        this.authToken = authToken;
    }

    @Override
    public String platform() {
        return "apns";
    }

    @Override
    public void send(String deviceToken, PushNotification notification) {
        String payload = Json.write(Map.of(
                "aps", Map.of(
                        "alert", Map.of("title", notification.title(), "body", notification.body()),
                        "badge", notification.badge(),
                        "sound", "default",
                        "thread-id", notification.conversationId()),
                "conversationId", notification.conversationId()));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + "/3/device/" + deviceToken))
                .header("authorization", "bearer " + authToken)
                .header("apns-topic", properties.getApnsBundleId())
                .header("apns-push-type", "alert")
                // Collapsing by conversation means a user who comes back after twenty messages sees one
                // notification for that chat, not twenty.
                .header("apns-collapse-id", notification.collapseKey())
                // If it has not been delivered within the hour it is stale; the app will fetch history.
                .header("apns-expiration", String.valueOf(System.currentTimeMillis() / 1000 + 3600))
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 410) {
                // The device unregistered. Dropping the token now keeps the next million pushes honest.
                tokens.unregister(notification.userId(), platform(), deviceToken);
            } else if (response.statusCode() >= 400) {
                log.warn("APNs rejected a push for {}: {} {}", notification.userId(), response.statusCode(), response.body());
            }
        } catch (Exception e) {
            // A failed push is not a failed message: it is already stored and will be read on next open.
            log.warn("APNs call failed for {}: {}", notification.userId(), e.toString());
            Thread.currentThread().interrupt();
        }
    }
}
