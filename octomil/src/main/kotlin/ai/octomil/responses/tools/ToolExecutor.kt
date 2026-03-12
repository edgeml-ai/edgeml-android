package ai.octomil.responses.tools

import ai.octomil.responses.ResponseToolCall

interface ToolExecutor {
    suspend fun execute(call: ResponseToolCall): ToolResult
}

data class ToolResult(
    val toolCallId: String,
    val content: String,
    val isError: Boolean = false,
)
