package dev.dwij.chat.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * What notification-service publishes on a gateway node's Redis channel. The gateway that owns the
 * recipient's socket picks it up and writes it to the wire.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeliveryEnvelope(String recipientId, ChatMessage message) {
}
