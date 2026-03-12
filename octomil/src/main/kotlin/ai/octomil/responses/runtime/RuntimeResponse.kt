package ai.octomil.responses.runtime

data class RuntimeResponse(
    val text: String,
    val toolCalls: List<RuntimeToolCall>? = null,
    val finishReason: String = "stop",
    val usage: RuntimeUsage? = null,
)

data class RuntimeToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

data class RuntimeUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
)
