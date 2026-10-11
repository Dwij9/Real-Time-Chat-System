package dev.dwij.chat.message.config;

import dev.dwij.chat.common.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Topic definitions, created automatically in local and test environments. Production topics are created
 * by infrastructure code with far higher partition counts; see docs/capacity-planning.md for the sizing.
 */
@Configuration
@Profile("local")
public class KafkaTopicConfig {

    @Bean
    public NewTopic chatMessagesTopic() {
        return TopicBuilder.name(Topics.CHAT_MESSAGES).partitions(12).replicas(1).build();
    }

    @Bean
    public NewTopic chatFanoutTopic() {
        return TopicBuilder.name(Topics.CHAT_FANOUT).partitions(12).replicas(1).build();
    }

    @Bean
    public NewTopic chatMessagesDlqTopic() {
        return TopicBuilder.name(Topics.CHAT_MESSAGES_DLQ).partitions(3).replicas(1).build();
    }
}
