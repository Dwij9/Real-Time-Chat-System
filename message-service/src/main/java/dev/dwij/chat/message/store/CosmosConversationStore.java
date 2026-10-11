package dev.dwij.chat.message.store;

import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.CosmosItemRequestOptions;
import com.azure.cosmos.models.PartitionKey;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.dwij.chat.common.model.Conversation;
import dev.dwij.chat.common.model.ConversationType;
import dev.dwij.chat.message.config.CosmosProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Conversation metadata, in its own container but partitioned the same way, so a lookup of "who is in this
 * chat" is also a single-partition point read keyed by the id the caller already has.
 */
@Repository
@ConditionalOnProperty(name = "chat.store", havingValue = "cosmos")
public class CosmosConversationStore implements ConversationStore {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ConversationDocument {
        private String id;
        private String conversationId;
        private ConversationType type;
        private List<String> members;
        private String title;
        private long createdAt;

        public ConversationDocument() {
        }

        static ConversationDocument from(Conversation conversation) {
            ConversationDocument document = new ConversationDocument();
            document.id = conversation.conversationId();
            document.conversationId = conversation.conversationId();
            document.type = conversation.type();
            document.members = conversation.members();
            document.title = conversation.title();
            document.createdAt = conversation.createdAt();
            return document;
        }

        Conversation toConversation() {
            return new Conversation(conversationId, type, members, title, createdAt);
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getConversationId() {
            return conversationId;
        }

        public void setConversationId(String conversationId) {
            this.conversationId = conversationId;
        }

        public ConversationType getType() {
            return type;
        }

        public void setType(ConversationType type) {
            this.type = type;
        }

        public List<String> getMembers() {
            return members;
        }

        public void setMembers(List<String> members) {
            this.members = members;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(long createdAt) {
            this.createdAt = createdAt;
        }
    }

    private final CosmosContainer container;

    public CosmosConversationStore(CosmosClient client, CosmosProperties properties) {
        this.container = client.getDatabase(properties.getDatabase())
                .getContainer(properties.getConversationContainer());
    }

    @Override
    public Conversation create(Conversation conversation) {
        container.upsertItem(
                ConversationDocument.from(conversation),
                new PartitionKey(conversation.conversationId()),
                new CosmosItemRequestOptions());
        return conversation;
    }

    @Override
    public Optional<Conversation> find(String conversationId) {
        try {
            ConversationDocument document = container.readItem(
                    conversationId,
                    new PartitionKey(conversationId),
                    ConversationDocument.class).getItem();
            return Optional.ofNullable(document).map(ConversationDocument::toConversation);
        } catch (CosmosException e) {
            if (e.getStatusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public List<String> members(String conversationId) {
        return find(conversationId).map(Conversation::members).orElse(List.of());
    }
}
