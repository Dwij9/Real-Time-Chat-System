package dev.dwij.chat.notification.push;

public interface PushSender {

    /** @return the platform this sender handles: "apns" or "fcm". */
    String platform();

    void send(String deviceToken, PushNotification notification);
}
