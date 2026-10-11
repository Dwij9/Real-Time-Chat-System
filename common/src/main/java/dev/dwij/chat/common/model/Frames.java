package dev.dwij.chat.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The wire protocol between a client and the gateway: plain JSON text frames, one object per frame. */
public final class Frames {

    private Frames() {
    }

    /* Client -> gateway */
    public static final String SEND = "send";
    public static final String HEARTBEAT = "heartbeat";
    public static final String READ_RECEIPT = "read";

    /* Gateway -> client */
    public static final String CONNECTED = "connected";
    public static final String QUEUED = "queued";
    public static final String MESSAGE = "message";
    public static final String PONG = "pong";
    public static final String ERROR = "error";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Inbound(
            String type,
            String messageId,
            String conversationId,
            MessageType messageType,
            String body,
            Long sentAt,
            Long upToSeq) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Outbound(
            String type,
            String messageId,
            String conversationId,
            Long serverSeq,
            ChatMessage message,
            String detail) {

        public static Outbound connected(String userId, String nodeId) {
            return new Outbound(CONNECTED, null, null, null, null, userId + "@" + nodeId);
        }

        public static Outbound queued(String messageId, String conversationId) {
            return new Outbound(QUEUED, messageId, conversationId, null, null, null);
        }

        public static Outbound message(ChatMessage message) {
            return new Outbound(MESSAGE, message.getMessageId(), message.getConversationId(),
                    message.getServerSeq(), message, null);
        }

        public static Outbound pong() {
            return new Outbound(PONG, null, null, null, null, null);
        }

        public static Outbound error(String detail) {
            return new Outbound(ERROR, null, null, null, null, detail);
        }
    }
}
