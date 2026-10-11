package dev.dwij.chat.notification.push;

public record PushNotification(
        String userId,
        String conversationId,
        String title,
        String body,
        long badge,
        String collapseKey) {
}
