package dev.dwij.chat.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One event per recipient, produced only after the message is durably stored.
 *
 * <p>Keyed on recipientId so every event for one person lands on one partition and is handled in order by
 * one consumer. A user never sees message 12 before message 11 because of a partition race.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FanoutEvent(
        String recipientId,
        ChatMessage message,
        String senderDisplayName,
        String conversationTitle) {
}
