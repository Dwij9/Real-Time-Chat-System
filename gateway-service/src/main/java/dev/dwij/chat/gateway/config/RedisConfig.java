package dev.dwij.chat.gateway.config;

import dev.dwij.chat.gateway.delivery.DeliverySubscriber;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import dev.dwij.chat.common.RedisKeys;

@Configuration
public class RedisConfig {

    /**
     * Each gateway node subscribes to exactly one channel, named after itself. Delivery is therefore a
     * direct hop to the node holding the socket rather than a broadcast every node has to filter.
     */
    @Bean
    public RedisMessageListenerContainer deliveryListenerContainer(RedisConnectionFactory connectionFactory,
                                                                   DeliverySubscriber subscriber,
                                                                   GatewayProperties properties) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(RedisKeys.gatewayChannel(properties.getNodeId())));
        return container;
    }
}
