package dev.dwij.chat.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Conversation(
        String conversationId,
        ConversationType type,
        List<String> members,
        String title,
        long createdAt) {
}
