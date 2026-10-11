package dev.dwij.chat.message.config;

import dev.dwij.chat.common.model.Conversation;
import dev.dwij.chat.common.model.ConversationType;
import dev.dwij.chat.message.store.ConversationStore;
import dev.dwij.chat.message.store.MemberDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.List;

/**
 * Creates the conversation the bundled demo console uses, so a local run is immediately interactive
 * instead of starting with an empty database and a 404.
 */
@Configuration
@Profile("local")
public class LocalSeed {

    private static final Logger log = LoggerFactory.getLogger(LocalSeed.class);

    @Bean
    public ApplicationRunner seedDemoConversation(ConversationStore conversations, MemberDirectory members) {
        return args -> {
            Conversation demo = new Conversation(
                    "dm:alice:bob",
                    ConversationType.DIRECT,
                    List.of("alice", "bob"),
                    null,
                    System.currentTimeMillis());
            conversations.create(demo);
            members.invalidate(demo.conversationId());
            log.info("Seeded demo conversation {}", demo.conversationId());
        };
    }
}
