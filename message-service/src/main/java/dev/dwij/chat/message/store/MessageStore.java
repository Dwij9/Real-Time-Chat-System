package dev.dwij.chat.message.store;

import dev.dwij.chat.common.model.ChatMessage;

import java.util.List;

/**
 * Durable message history.
 *
 * <p>Two implementations: Cosmos DB for anything real, and an in-memory one so the whole system can be run
 * and demonstrated locally without an Azure subscription.
 */
public interface MessageStore {

    /**
     * Writes the message, overwriting any existing row with the same id. Upsert rather than insert so a
     * Kafka replay of an already-stored message is a no-op instead of an error.
     */
    void save(ChatMessage message);

    /**
     * Newest first, optionally paging backwards from a sequence number.
     *
     * @param beforeSeq exclusive upper bound, or null for the newest page
     */
    List<ChatMessage> history(String conversationId, Long beforeSeq, int limit);
}
