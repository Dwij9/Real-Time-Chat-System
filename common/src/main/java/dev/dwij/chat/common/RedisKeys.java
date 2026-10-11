package dev.dwij.chat.common;

/**
 * Every Redis key the system uses, in one place.
 *
 * <p>Keys that must live on the same cluster slot use a hash tag around the conversation id, so the recent
 * cache, the sequence counter and the member set for one conversation are always on one shard and can be
 * touched in a single pipelined round trip.
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    /** presence:{userId} -> gateway node id. Written with a 30s TTL and refreshed by heartbeat. */
    public static String presence(String userId) {
        return "presence:" + userId;
    }

    /** Redis pub/sub channel one gateway node listens on for messages destined to its own sockets. */
    public static String gatewayChannel(String nodeId) {
        return "gw:" + nodeId;
    }

    /** Sorted set of the last N messages of a conversation, scored by serverSeq. */
    public static String recentMessages(String conversationId) {
        return "conv:{" + conversationId + "}:recent";
    }

    /** Monotonic per-conversation sequence counter. */
    public static String sequence(String conversationId) {
        return "conv:{" + conversationId + "}:seq";
    }

    /** Member set of a conversation, cached in front of Cosmos DB. */
    public static String members(String conversationId) {
        return "conv:{" + conversationId + "}:members";
    }

    /** Idempotency marker holding the serverSeq already assigned to this message id. */
    public static String assignedSequence(String messageId) {
        return "seq-of:" + messageId;
    }

    /** Device push tokens for a user, as a set of "platform|token" entries. */
    public static String deviceTokens(String userId) {
        return "devices:" + userId;
    }

    /** Unread counter used for the push badge. */
    public static String unread(String userId, String conversationId) {
        return "unread:" + userId + ":" + conversationId;
    }
}
