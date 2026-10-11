package dev.dwij.chat.message.store;

import dev.dwij.chat.common.model.Conversation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@ConditionalOnProperty(name = "chat.store", havingValue = "memory", matchIfMissing = true)
public class InMemoryConversationStore implements ConversationStore {

    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();

    @Override
    public Conversation create(Conversation conversation) {
        conversations.put(conversation.conversationId(), conversation);
        return conversation;
    }

    @Override
    public Optional<Conversation> find(String conversationId) {
        return Optional.ofNullable(conversations.get(conversationId));
    }

    @Override
    public List<String> members(String conversationId) {
        Conversation conversation = conversations.get(conversationId);
        return conversation == null ? List.of() : conversation.members();
    }
}
