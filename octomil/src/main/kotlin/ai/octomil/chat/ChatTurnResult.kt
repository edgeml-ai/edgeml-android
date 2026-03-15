package ai.octomil.chat

/**
 * Result of a chat turn containing the user and assistant messages.
 *
 * Matches `octomil.chat_turn_result` contract schema.
 */
data class ChatTurnResult(
    val userMessage: ThreadMessage,
    val assistantMessage: ThreadMessage,
)
