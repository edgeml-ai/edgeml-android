package ai.octomil.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Lifecycle status of a tool call.
 */
@Serializable
enum class ToolCallStatus {
    @SerialName("requested") REQUESTED,
    @SerialName("started") STARTED,
    @SerialName("succeeded") SUCCEEDED,
    @SerialName("failed") FAILED,
    @SerialName("expired") EXPIRED,
}

/**
 * Domain-level tool call entity.
 *
 * The existing [LegacyToolCall] in Tool.kt is the OpenAI wire format.
 * This type is the canonical internal representation.
 */
data class DomainToolCall(
    val id: String,
    val messageId: String,
    val threadId: String? = null,
    val name: String,
    val arguments: String? = null,
    val argumentsRef: String? = null,
    val status: ToolCallStatus? = null,
    val startedAt: String? = null,
    val endedAt: String? = null,
    val latencyMs: Int? = null,
    val errorCode: String? = null,
)
