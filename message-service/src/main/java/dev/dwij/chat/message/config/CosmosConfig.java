package dev.dwij.chat.message.config;

import com.azure.cosmos.ConsistencyLevel;
import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.ThrottlingRetryOptions;
import com.azure.cosmos.models.CosmosContainerProperties;
import com.azure.cosmos.models.ThroughputProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(CosmosProperties.class)
@ConditionalOnProperty(name = "chat.store", havingValue = "cosmos")
public class CosmosConfig {

    @Bean(destroyMethod = "close")
    public CosmosClient cosmosClient(CosmosProperties properties) {
        return new CosmosClientBuilder()
                .endpoint(properties.getEndpoint())
                .key(properties.getKey())
                // Session consistency: a client always reads its own writes, while replication across
                // regions stays asynchronous. Strong consistency here would mean a cross-region quorum on
                // the write path, which at 70k writes a second is both slow and, for chat, unnecessary.
                .consistencyLevel(ConsistencyLevel.SESSION)
                .preferredRegions(properties.getPreferredRegions())
                .multipleWriteRegionsEnabled(properties.isMultipleWriteRegions())
                // Direct mode talks TCP straight to the replicas instead of through the gateway endpoint,
                // which is what keeps p99 write latency in single-digit milliseconds at this volume.
                .directMode()
                .throttlingRetryOptions(new ThrottlingRetryOptions()
                        .setMaxRetryAttemptsOnThrottledRequests(9)
                        .setMaxRetryWaitTime(Duration.ofSeconds(30)))
                .contentResponseOnWriteEnabled(false)
                .buildClient();
    }

    /**
     * Creates the containers if they are missing, so a fresh environment comes up ready.
     *
     * <p>Autoscale rather than fixed throughput: traffic has a daily shape with a 3x peak, and paying for
     * peak capacity around the clock would roughly triple the database bill.
     */
    @Bean
    public CosmosSchemaInitializer cosmosSchemaInitializer(CosmosClient client, CosmosProperties properties) {
        return new CosmosSchemaInitializer(client, properties);
    }

    public static class CosmosSchemaInitializer {

        public CosmosSchemaInitializer(CosmosClient client, CosmosProperties properties) {
            client.createDatabaseIfNotExists(properties.getDatabase());
            var database = client.getDatabase(properties.getDatabase());

            CosmosContainerProperties messages =
                    new CosmosContainerProperties(properties.getMessageContainer(), "/conversationId");
            if (properties.getRetentionDays() > 0) {
                messages.setDefaultTimeToLiveInSeconds(properties.getRetentionDays() * 86_400);
            }
            database.createContainerIfNotExists(messages, ThroughputProperties.createAutoscaledThroughput(4000));

            CosmosContainerProperties conversations =
                    new CosmosContainerProperties(properties.getConversationContainer(), "/conversationId");
            database.createContainerIfNotExists(conversations, ThroughputProperties.createAutoscaledThroughput(4000));
        }
    }
}
