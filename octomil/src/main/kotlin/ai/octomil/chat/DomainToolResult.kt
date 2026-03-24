package ai.octomil.chat

/**
 * Result of a tool call execution.
 *
 * [toolCallId] is NOT unique-constrained to allow streaming results,
 * retries, and multi-part outputs.
 */
data class DomainToolResult(
    val id: String,
    val toolCallId: String,
    val messageId: String? = null,
    val output: String? = null,
    val outputRef: String? = null,
    val status: ToolCallStatus? = null,
    val sizeBytes: Int? = null,
    val isFinal: Boolean = true,
)
