package dev.dwij.chat.notification.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stands in for the real providers when running locally, so the offline path can be seen working without
 * APNs certificates or Firebase credentials.
 */
@Component
@ConditionalOnProperty(name = "chat.notification.push-provider", havingValue = "log", matchIfMissing = true)
public class LoggingPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingPushSender.class);

    @Override
    public String platform() {
        return "*";
    }

    @Override
    public void send(String deviceToken, PushNotification notification) {
        log.info("PUSH -> {} [{}] {}: {} (badge {})",
                deviceToken, notification.conversationId(), notification.title(),
                notification.body(), notification.badge());
    }
}
