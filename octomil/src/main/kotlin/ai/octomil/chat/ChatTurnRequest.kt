package ai.octomil.chat

import ai.octomil.responses.ContentPart

/**
 * Request to submit a user turn and receive an assistant response.
 *
 * Matches `octomil.chat_turn_request` contract schema.
 */
data class ChatTurnRequest(
    val threadId: String,
    val input: String,
    val inputParts: List<ContentPart>? = null,
    val config: ChatTurnConfig? = null,
)

/**
 * Optional generation configuration for a chat turn.
 */
data class ChatTurnConfig(
    val maxTokens: Int? = null,
    val temperature: Float? = null,
    val topP: Float? = null,
    val stop: List<String>? = null,
)
