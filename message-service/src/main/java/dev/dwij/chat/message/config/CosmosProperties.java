package dev.dwij.chat.message.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "chat.cosmos")
public class CosmosProperties {

    private String endpoint;
    private String key;
    private String database = "chat";
    private String messageContainer = "messages";
    private String conversationContainer = "conversations";

    /**
     * Read region order. The client sends reads to the first reachable one, so a region losing its
     * database replica silently shifts to the next without a config push or a restart.
     */
    private List<String> preferredRegions = List.of();

    /** Writes accepted in more than one region. Required for the hot standby to take writes on failover. */
    private boolean multipleWriteRegions = true;

    /** Days before a message row expires, or 0 to keep forever. */
    private int retentionDays = 0;

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getMessageContainer() {
        return messageContainer;
    }

    public void setMessageContainer(String messageContainer) {
        this.messageContainer = messageContainer;
    }

    public String getConversationContainer() {
        return conversationContainer;
    }

    public void setConversationContainer(String conversationContainer) {
        this.conversationContainer = conversationContainer;
    }

    public List<String> getPreferredRegions() {
        return preferredRegions;
    }

    public void setPreferredRegions(List<String> preferredRegions) {
        this.preferredRegions = preferredRegions;
    }

    public boolean isMultipleWriteRegions() {
        return multipleWriteRegions;
    }

    public void setMultipleWriteRegions(boolean multipleWriteRegions) {
        this.multipleWriteRegions = multipleWriteRegions;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }
}
