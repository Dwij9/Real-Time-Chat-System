package dev.dwij.chat.notification.push;

import dev.dwij.chat.common.model.ChatMessage;
import dev.dwij.chat.notification.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Builds the notification and sends it to every device the user has registered. */
@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);

    private final DeviceTokenStore tokens;
    private final NotificationProperties properties;
    private final Map<String, PushSender> senders;
    private final PushSender fallbackSender;

    public PushService(DeviceTokenStore tokens, NotificationProperties properties, List<PushSender> senders) {
        this.tokens = tokens;
        this.properties = properties;
        this.senders = senders.stream()
                .filter(sender -> !"*".equals(sender.platform()))
                .collect(Collectors.toMap(PushSender::platform, Function.identity()));
        this.fallbackSender = senders.stream()
                .filter(sender -> "*".equals(sender.platform()))
                .findFirst()
                .orElse(null);
    }

    public void notifyOffline(String userId, ChatMessage message, String senderName, String conversationTitle) {
        List<DeviceTokenStore.Device> devices = tokens.devices(userId);
        if (devices.isEmpty()) {
            // Nothing to push to: a web-only user, or someone who never granted permission. The message is
            // already durable and will be there when they next open the app.
            return;
        }

        long badge = tokens.incrementUnread(userId, message.getConversationId());
        PushNotification notification = new PushNotification(
                userId,
                message.getConversationId(),
                conversationTitle == null ? senderName : conversationTitle,
                preview(message, senderName, conversationTitle != null),
                badge,
                message.getConversationId());

        for (DeviceTokenStore.Device device : devices) {
            PushSender sender = senders.getOrDefault(device.platform(), fallbackSender);
            if (sender == null) {
                log.warn("No push sender configured for platform {}", device.platform());
                continue;
            }
            sender.send(device.token(), notification);
        }
    }

    private String preview(ChatMessage message, String senderName, boolean isGroup) {
        if (!properties.isIncludePreviewInPush()) {
            return isGroup ? "New message in this conversation" : "New message";
        }
        String body = switch (message.getType()) {
            case TEXT -> message.getBody();
            case IMAGE -> "Sent a photo";
            case FILE -> "Sent a file";
            case SYSTEM -> message.getBody();
        };
        if (body == null) {
            body = "New message";
        }
        String prefixed = isGroup ? senderName + ": " + body : body;
        return prefixed.length() <= properties.getPushPreviewLength()
                ? prefixed
                : prefixed.substring(0, properties.getPushPreviewLength() - 1) + "\u2026";
    }
}
