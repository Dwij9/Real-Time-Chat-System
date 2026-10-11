package dev.dwij.chat.message;

import dev.dwij.chat.common.model.ChatMessage;
import dev.dwij.chat.common.model.MessageType;
import dev.dwij.chat.message.store.InMemoryMessageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryMessageStoreTest {

    private InMemoryMessageStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryMessageStore();
        for (int seq = 1; seq <= 120; seq++) {
            store.save(message("m" + seq, "conv-1", seq));
        }
        store.save(message("other", "conv-2", 1));
    }

    @Test
    void returnsNewestFirst() {
        List<ChatMessage> page = store.history("conv-1", null, 50);

        assertEquals(50, page.size());
        assertEquals(120, page.get(0).getServerSeq());
        assertEquals(71, page.get(49).getServerSeq());
    }

    @Test
    void pagesBackwardsFromASequence() {
        List<ChatMessage> page = store.history("conv-1", 71L, 50);

        assertEquals(70, page.get(0).getServerSeq());
        assertTrue(page.stream().allMatch(message -> message.getServerSeq() < 71));
    }

    @Test
    void keepsPartitionsApart() {
        assertEquals(1, store.history("conv-2", null, 50).size());
    }

    @Test
    void rewritingTheSameIdDoesNotDuplicate() {
        store.save(message("m120", "conv-1", 120));
        assertEquals(120, store.history("conv-1", null, 200).size());
    }

    private ChatMessage message(String id, String conversationId, long seq) {
        ChatMessage message = new ChatMessage(id, conversationId, "alice", MessageType.TEXT, "hi", seq);
        message.setServerSeq(seq);
        return message;
    }
}
