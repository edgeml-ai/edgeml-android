package ai.octomil.chat

import ai.octomil.responses.ContentPart

/**
 * A single message within a chat thread.
 *
 * Named `ThreadMessage` to avoid collision with the OpenAI-compat [ChatMessage].
 * Message IDs use the `msg_` prefix. Matches `octomil.chat_message` contract schema.
 */
data class ThreadMessage(
    val id: String,
    val threadId: String,
    val role: String,
    val content: String? = null,
    val contentParts: List<ContentPart>? = null,
    val toolCalls: List<LegacyToolCall>? = null,
    val toolCallId: String? = null,
    val metrics: GenerationMetrics? = null,
    val createdAt: String,
    val parentMessageId: String? = null,
    val status: String? = null,
    val modelRef: String? = null,
    val storageMode: String? = null,
)
