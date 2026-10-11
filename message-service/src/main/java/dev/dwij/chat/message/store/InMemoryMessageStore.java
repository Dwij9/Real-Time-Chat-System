package dev.dwij.chat.message.store;

import dev.dwij.chat.common.model.ChatMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stand-in for Cosmos DB when running locally. Keeps the same shape as the real store, including the
 * partition-by-conversation layout, so the code paths exercised in a demo are the ones that run in Azure.
 */
@Repository
@ConditionalOnProperty(name = "chat.store", havingValue = "memory", matchIfMissing = true)
public class InMemoryMessageStore implements MessageStore {

    /** Outer key is the partition key. Inner map is keyed by message id, which gives upsert semantics. */
    private final Map<String, Map<String, ChatMessage>> partitions = new ConcurrentHashMap<>();

    @Override
    public void save(ChatMessage message) {
        partitions.computeIfAbsent(message.getConversationId(), key -> new ConcurrentHashMap<>())
                .put(message.getMessageId(), message);
    }

    @Override
    public List<ChatMessage> history(String conversationId, Long beforeSeq, int limit) {
        Map<String, ChatMessage> partition = partitions.get(conversationId);
        if (partition == null) {
            return List.of();
        }
        return new ArrayList<>(partition.values()).stream()
                .filter(message -> beforeSeq == null || message.getServerSeq() < beforeSeq)
                .sorted(Comparator.comparingLong(ChatMessage::getServerSeq).reversed())
                .limit(limit)
                .toList();
    }
}
