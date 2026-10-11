package dev.dwij.chat.message.api;

import dev.dwij.chat.common.model.ChatMessage;
import dev.dwij.chat.message.cache.RecentMessageCache;
import dev.dwij.chat.message.config.MessageProperties;
import dev.dwij.chat.message.store.MessageStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Reads, cache first.
 *
 * <p>The newest page of a conversation is what a client asks for on nearly every open, and that is exactly
 * what the cache holds, so the database sees only scrollback and cold conversations. Any request for older
 * messages, or for a page larger than the cache keeps, goes straight to the store; the cache is never
 * consulted for a question it cannot fully answer, which avoids stitching two sources together and the
 * ordering bugs that come with it.
 */
@Service
public class HistoryService {

    private final RecentMessageCache cache;
    private final MessageStore store;
    private final MessageProperties properties;
    private final Counter cacheHits;
    private final Counter cacheMisses;

    public HistoryService(RecentMessageCache cache, MessageStore store, MessageProperties properties,
                          MeterRegistry meters) {
        this.cache = cache;
        this.store = store;
        this.properties = properties;
        this.cacheHits = Counter.builder("chat.history.cache.hit").register(meters);
        this.cacheMisses = Counter.builder("chat.history.cache.miss").register(meters);
    }

    public List<ChatMessage> history(String conversationId, Long beforeSeq, int limit) {
        int capped = Math.min(Math.max(limit, 1), 200);
        if (beforeSeq == null && capped <= properties.getRecentCacheSize()) {
            List<ChatMessage> cached = cache.newest(conversationId, capped);
            if (cached.size() == capped) {
                cacheHits.increment();
                return cached;
            }
            // A short result can mean a cold cache or a conversation that genuinely has fewer messages.
            // Those are indistinguishable from here, so the store settles it.
            if (!cached.isEmpty() && cache.size(conversationId) >= capped) {
                cacheHits.increment();
                return cached;
            }
        }
        cacheMisses.increment();
        return store.history(conversationId, beforeSeq, capped);
    }
}
