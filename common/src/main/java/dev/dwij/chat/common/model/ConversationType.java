package dev.dwij.chat.common.model;

public enum ConversationType {
    /** Exactly two members. */
    DIRECT,
    /** Many members, bounded by chat.conversation.max-group-size. */
    GROUP
}
