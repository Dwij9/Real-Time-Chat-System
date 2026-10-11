package dev.dwij.chat.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat.notification")
public class NotificationProperties {

    /** Characters of the message shown in a push notification before truncation. */
    private int pushPreviewLength = 120;

    /** When false, the push body is replaced with a generic line. Set per tenant for privacy. */
    private boolean includePreviewInPush = true;

    private String apnsBundleId = "com.example.chat";
    private String fcmProjectId = "example-chat";

    public int getPushPreviewLength() {
        return pushPreviewLength;
    }

    public void setPushPreviewLength(int pushPreviewLength) {
        this.pushPreviewLength = pushPreviewLength;
    }

    public boolean isIncludePreviewInPush() {
        return includePreviewInPush;
    }

    public void setIncludePreviewInPush(boolean includePreviewInPush) {
        this.includePreviewInPush = includePreviewInPush;
    }

    public String getApnsBundleId() {
        return apnsBundleId;
    }

    public void setApnsBundleId(String apnsBundleId) {
        this.apnsBundleId = apnsBundleId;
    }

    public String getFcmProjectId() {
        return fcmProjectId;
    }

    public void setFcmProjectId(String fcmProjectId) {
        this.fcmProjectId = fcmProjectId;
    }
}
