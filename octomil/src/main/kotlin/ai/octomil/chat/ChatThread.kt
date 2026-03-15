package ai.octomil.chat

/**
 * A conversation thread containing messages.
 *
 * Thread IDs use the `thread_` prefix. Matches `octomil.chat_thread` contract schema.
 */
data class ChatThread(
    val id: String,
    val title: String? = null,
    val model: String,
    val createdAt: String,
    val updatedAt: String,
    val metadata: Map<String, String> = emptyMap(),
)
