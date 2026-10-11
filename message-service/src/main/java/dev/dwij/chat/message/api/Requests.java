package dev.dwij.chat.message.api;

import dev.dwij.chat.common.model.ChatMessage;
import dev.dwij.chat.common.model.ConversationType;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public final class Requests {

    private Requests() {
    }

    public record CreateConversation(
            @NotNull ConversationType type,
            @NotEmpty List<String> members,
            String title) {
    }

    public record MessagePage(List<ChatMessage> messages, Long nextBeforeSeq, boolean servedFromCache) {
    }
}
