package ai.octomil.chat

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
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null,
    val metrics: GenerationMetrics? = null,
    val createdAt: String,
)
