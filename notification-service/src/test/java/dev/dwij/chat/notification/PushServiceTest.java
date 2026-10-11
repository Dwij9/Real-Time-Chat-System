package dev.dwij.chat.notification;

import dev.dwij.chat.common.model.ChatMessage;
import dev.dwij.chat.common.model.MessageType;
import dev.dwij.chat.notification.config.NotificationProperties;
import dev.dwij.chat.notification.push.DeviceTokenStore;
import dev.dwij.chat.notification.push.PushNotification;
import dev.dwij.chat.notification.push.PushSender;
import dev.dwij.chat.notification.push.PushService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PushServiceTest {

    private final List<PushNotification> sent = new ArrayList<>();

    private final PushSender recordingSender = new PushSender() {
        @Override
        public String platform() {
            return "apns";
        }

        @Override
        public void send(String deviceToken, PushNotification notification) {
            sent.add(notification);
        }
    };

    @Test
    void pushesToEveryRegisteredDeviceWithAnIncrementingBadge() {
        DeviceTokenStore tokens = mock(DeviceTokenStore.class);
        when(tokens.devices("bob")).thenReturn(List.of(
                new DeviceTokenStore.Device("apns", "token-phone"),
                new DeviceTokenStore.Device("apns", "token-tablet")));
        when(tokens.incrementUnread("bob", "conv-1")).thenReturn(3L);

        service(tokens, new NotificationProperties())
                .notifyOffline("bob", message("Standup moved to 10"), "Alice", null);

        assertEquals(2, sent.size());
        assertEquals(3L, sent.get(0).badge());
        assertEquals("Standup moved to 10", sent.get(0).body());
    }

    @Test
    void sendsNothingWhenTheUserHasNoDevices() {
        DeviceTokenStore tokens = mock(DeviceTokenStore.class);
        when(tokens.devices("bob")).thenReturn(List.of());

        service(tokens, new NotificationProperties()).notifyOffline("bob", message("hi"), "Alice", null);

        assertTrue(sent.isEmpty());
    }

    @Test
    void hidesTheMessageWhenPreviewsAreTurnedOff() {
        DeviceTokenStore tokens = mock(DeviceTokenStore.class);
        when(tokens.devices("bob")).thenReturn(List.of(new DeviceTokenStore.Device("apns", "t")));
        NotificationProperties properties = new NotificationProperties();
        properties.setIncludePreviewInPush(false);

        service(tokens, properties).notifyOffline("bob", message("salary details"), "Alice", null);

        assertEquals("New message", sent.get(0).body());
    }

    @Test
    void namesTheSenderInAGroupAndTruncatesLongBodies() {
        DeviceTokenStore tokens = mock(DeviceTokenStore.class);
        when(tokens.devices("bob")).thenReturn(List.of(new DeviceTokenStore.Device("apns", "t")));
        NotificationProperties properties = new NotificationProperties();
        properties.setPushPreviewLength(20);

        service(tokens, properties).notifyOffline("bob", message("x".repeat(100)), "Alice", "Platform team");

        PushNotification notification = sent.get(0);
        assertEquals("Platform team", notification.title());
        assertTrue(notification.body().startsWith("Alice: "));
        assertEquals(20, notification.body().length());
    }

    private PushService service(DeviceTokenStore tokens, NotificationProperties properties) {
        return new PushService(tokens, properties, List.of(recordingSender));
    }

    private ChatMessage message(String body) {
        return new ChatMessage("m1", "conv-1", "alice", MessageType.TEXT, body, System.currentTimeMillis());
    }
}
