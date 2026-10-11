package dev.dwij.chat.message.store;

import dev.dwij.chat.common.model.Conversation;

import java.util.List;
import java.util.Optional;

public interface ConversationStore {

    Conversation create(Conversation conversation);

    Optional<Conversation> find(String conversationId);

    List<String> members(String conversationId);
}
