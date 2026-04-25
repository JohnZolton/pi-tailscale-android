package com.pinostr.app.model

import java.util.UUID

/**
 * A single message in the chat UI.
 * Can be a user message, an assistant text response,
 * or a rendered stream event (tool call, thinking, diff).
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val text: String = "",
    val html: String = "",             // pre-rendered markdown HTML
    val timestamp: Long = System.currentTimeMillis(),
    val replyToNpub: String = "",      // thread npub this was sent to / received from

    // Stream event metadata
    val eventType: EventType = EventType.TEXT,
    val toolCallId: String = "",
    val toolName: String = "",
    val toolStatus: String = "",
    val diffContent: String = "",
    val diffFile: String = "",
    val thinkingText: String = "",
    val isStreaming: Boolean = false,
) {
    enum class Role { USER, ASSISTANT, SYSTEM }
    enum class EventType {
        TEXT,           // regular message
        THINKING,       // thinking/delta block
        TOOL_CALL,      // tool call card
        DIFF,           // diff card
        TURN_COMPLETE,  // turn marker
        STATUS,         // status update
    }
}
