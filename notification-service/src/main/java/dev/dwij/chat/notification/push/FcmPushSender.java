package dev.dwij.chat.notification.push;

import dev.dwij.chat.common.json.Json;
import dev.dwij.chat.notification.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Firebase Cloud Messaging, the Android half of the offline path.
 *
 * <p>Sent with high priority so Android wakes the device out of Doze for a chat message, and with a
 * collapse key per conversation so a backlog arrives as one notification rather than a stack of them.
 */
@Component
@ConditionalOnProperty(name = "chat.notification.push-provider", havingValue = "fcm")
public class FcmPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(FcmPushSender.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final NotificationProperties properties;
    private final DeviceTokenStore tokens;
    private final String accessToken;

    public FcmPushSender(NotificationProperties properties,
                         DeviceTokenStore tokens,
                         @Value("${chat.notification.fcm-access-token:}") String accessToken) {
        this.properties = properties;
        this.tokens = tokens;
        this.accessToken = accessToken;
    }

    @Override
    public String platform() {
        return "fcm";
    }

    @Override
    public void send(String deviceToken, PushNotification notification) {
        String payload = Json.write(Map.of("message", Map.of(
                "token", deviceToken,
                "notification", Map.of("title", notification.title(), "body", notification.body()),
                "data", Map.of("conversationId", notification.conversationId()),
                "android", Map.of(
                        "priority", "high",
                        "collapse_key", notification.collapseKey(),
                        "ttl", "3600s"))));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://fcm.googleapis.com/v1/projects/" + properties.getFcmProjectId() + "/messages:send"))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                tokens.unregister(notification.userId(), platform(), deviceToken);
            } else if (response.statusCode() >= 400) {
                log.warn("FCM rejected a push for {}: {} {}", notification.userId(), response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.warn("FCM call failed for {}: {}", notification.userId(), e.toString());
            Thread.currentThread().interrupt();
        }
    }
}
