package dev.dwij.chat.message.store;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.dwij.chat.common.model.ChatMessage;
import dev.dwij.chat.common.model.MessageType;

/**
 * The Cosmos DB row.
 *
 * <p>Separate from {@link ChatMessage} for two reasons: Cosmos requires an {@code id} field, and the row
 * carries a {@code ttl} that the wire format has no business knowing about. Keeping them apart means the
 * storage layout can change without rewriting the contract four services share.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MessageDocument {

    /** Cosmos document id. Set to the client-generated messageId, which is what makes writes idempotent. */
    private String id;
    /** Partition key. */
    private String conversationId;
    private String senderId;
    private MessageType type;
    private String body;
    private long sentAt;
    private long serverSeq;
    private long storedAt;
    /** Per-document expiry in seconds, honoured by Cosmos. Null means keep forever. */
    private Integer ttl;

    public MessageDocument() {
    }

    public static MessageDocument from(ChatMessage message, Integer ttlSeconds) {
        MessageDocument document = new MessageDocument();
        document.id = message.getMessageId();
        document.conversationId = message.getConversationId();
        document.senderId = message.getSenderId();
        document.type = message.getType();
        document.body = message.getBody();
        document.sentAt = message.getSentAt();
        document.serverSeq = message.getServerSeq();
        document.storedAt = message.getStoredAt();
        document.ttl = ttlSeconds;
        return document;
    }

    public ChatMessage toMessage() {
        ChatMessage message = new ChatMessage(id, conversationId, senderId, type, body, sentAt);
        message.setServerSeq(serverSeq);
        message.setStoredAt(storedAt);
        return message;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getSenderId() {
        return senderId;
    }

    public void setSenderId(String senderId) {
        this.senderId = senderId;
    }

    public MessageType getType() {
        return type;
    }

    public void setType(MessageType type) {
        this.type = type;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public long getSentAt() {
        return sentAt;
    }

    public void setSentAt(long sentAt) {
        this.sentAt = sentAt;
    }

    public long getServerSeq() {
        return serverSeq;
    }

    public void setServerSeq(long serverSeq) {
        this.serverSeq = serverSeq;
    }

    public long getStoredAt() {
        return storedAt;
    }

    public void setStoredAt(long storedAt) {
        this.storedAt = storedAt;
    }

    public Integer getTtl() {
        return ttl;
    }

    public void setTtl(Integer ttl) {
        this.ttl = ttl;
    }
}
