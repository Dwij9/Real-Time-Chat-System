package dev.dwij.chat.common;

/**
 * Kafka topic names and the partition key each topic is written with.
 *
 * <p>The partition key is the whole ordering story. CHAT_MESSAGES is keyed by conversation, so one chat's
 * messages are sequenced by a single consumer and can never be written out of order. CHAT_FANOUT is keyed
 * by recipient, so one person's incoming stream is likewise handled in order.
 */
public final class Topics {

    private Topics() {
    }

    /** Inbound, written by the gateway. Key: conversationId. */
    public static final String CHAT_MESSAGES = "chat.messages";

    /** One record per recipient, written by message-service after the durable write. Key: recipientId. */
    public static final String CHAT_FANOUT = "chat.fanout";

    /** Anything that fails past the retry budget, parked here rather than blocking the partition. */
    public static final String CHAT_MESSAGES_DLQ = "chat.messages.dlq";
}
