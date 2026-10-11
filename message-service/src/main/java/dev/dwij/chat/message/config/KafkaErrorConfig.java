package dev.dwij.chat.message.config;

import dev.dwij.chat.common.Topics;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class KafkaErrorConfig {

    /**
     * A poison message must not be allowed to block its partition.
     *
     * <p>Because the topic is keyed by conversation, a message that fails forever would stall every other
     * message in that chat behind it. Retries are bounded and backed off — enough to ride out a database
     * blip — and anything still failing is moved to a dead-letter topic so the partition keeps moving. The
     * parked records stay inspectable and can be replayed once the cause is fixed.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafka) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafka,
                (record, exception) -> new TopicPartition(Topics.CHAT_MESSAGES_DLQ, -1));

        ExponentialBackOff backOff = new ExponentialBackOff(200L, 2.0);
        backOff.setMaxElapsedTime(10_000L);
        return new DefaultErrorHandler(recoverer, backOff);
    }
}
