package dev.dwij.chat.message.store;

import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.models.CosmosItemRequestOptions;
import com.azure.cosmos.models.CosmosQueryRequestOptions;
import com.azure.cosmos.models.PartitionKey;
import com.azure.cosmos.models.SqlParameter;
import com.azure.cosmos.models.SqlQuerySpec;
import dev.dwij.chat.common.model.ChatMessage;
import dev.dwij.chat.message.config.CosmosProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * Messages in Cosmos DB, partitioned by conversation id.
 *
 * <p>The partition key choice is the single most consequential decision in the storage layer. Everything a
 * chat screen asks for — the last fifty messages of one conversation, in order — is answered from one
 * logical partition, so a history read is a single-partition query rather than a fan-out across every
 * physical partition in the account. Partitioning by user id instead would scatter a group chat across as
 * many partitions as it has members and make every read a cross-partition scan.
 *
 * <p>The cost is a per-partition ceiling. One logical partition is capped at 20 GB and 10k RU/s, so a
 * conversation that ran forever would eventually hit it. Long-lived conversations are therefore written as
 * {@code conversationId:epochBucket}, which keeps a chat's recent history together while bounding any one
 * partition. The bucket is resolved before this layer, so the store itself stays simple.
 */
@Repository
@ConditionalOnProperty(name = "chat.store", havingValue = "cosmos")
public class CosmosMessageStore implements MessageStore {

    private final CosmosContainer container;
    private final Integer ttlSeconds;

    public CosmosMessageStore(CosmosClient client, CosmosProperties properties) {
        this.container = client.getDatabase(properties.getDatabase())
                .getContainer(properties.getMessageContainer());
        this.ttlSeconds = properties.getRetentionDays() > 0
                ? properties.getRetentionDays() * 86_400
                : null;
    }

    @Override
    public void save(ChatMessage message) {
        // Upsert, not create. A replay from Kafka after a crash rewrites the identical row instead of
        // failing on a conflict, which is what lets the consumer be at-least-once without care.
        container.upsertItem(
                MessageDocument.from(message, ttlSeconds),
                new PartitionKey(message.getConversationId()),
                new CosmosItemRequestOptions());
    }

    @Override
    public List<ChatMessage> history(String conversationId, Long beforeSeq, int limit) {
        String sql = "SELECT * FROM c WHERE c.conversationId = @conversationId"
                + (beforeSeq == null ? "" : " AND c.serverSeq < @beforeSeq")
                + " ORDER BY c.serverSeq DESC OFFSET 0 LIMIT @limit";

        List<SqlParameter> parameters = new ArrayList<>();
        parameters.add(new SqlParameter("@conversationId", conversationId));
        parameters.add(new SqlParameter("@limit", limit));
        if (beforeSeq != null) {
            parameters.add(new SqlParameter("@beforeSeq", beforeSeq));
        }

        CosmosQueryRequestOptions options = new CosmosQueryRequestOptions()
                // Pinning the partition key keeps this a single-partition query: one replica answers it,
                // the RU cost stays flat as the account grows, and no cross-partition merge is needed.
                .setPartitionKey(new PartitionKey(conversationId))
                .setMaxBufferedItemCount(limit);

        List<ChatMessage> page = new ArrayList<>(limit);
        container.queryItems(new SqlQuerySpec(sql, parameters), options, MessageDocument.class)
                .iterableByPage(limit)
                .forEach(response -> response.getResults().forEach(document -> page.add(document.toMessage())));
        return page;
    }
}
