package dev.dwij.chat.message;

import dev.dwij.chat.common.json.Json;
import dev.dwij.chat.common.model.ChatMessage;
import dev.dwij.chat.common.model.MessageType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessageSerializationTest {

    @Test
    void survivesARoundTripThroughKafka() {
        ChatMessage original = new ChatMessage("m1", "conv-1", "alice", MessageType.TEXT, "hello", 1700000000000L);
        original.setServerSeq(42);
        original.setStoredAt(1700000000500L);

        ChatMessage parsed = Json.read(Json.write(original), ChatMessage.class);

        assertEquals(original.getMessageId(), parsed.getMessageId());
        assertEquals(original.getConversationId(), parsed.getConversationId());
        assertEquals(42, parsed.getServerSeq());
        assertEquals("hello", parsed.getBody());
    }

    @Test
    void toleratesAFieldAddedByANewerProducer() {
        String fromTheFuture = "{\"messageId\":\"m1\",\"conversationId\":\"c1\",\"senderId\":\"alice\","
                + "\"body\":\"hi\",\"serverSeq\":7,\"reactions\":[\"thumbsup\"]}";

        ChatMessage parsed = Json.read(fromTheFuture, ChatMessage.class);

        assertEquals(7, parsed.getServerSeq());
    }
}
