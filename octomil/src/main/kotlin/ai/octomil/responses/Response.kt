package ai.octomil.responses

data class Response(
    val id: String,
    val model: String,
    val output: List<OutputItem>,
    val finishReason: String,
    val usage: ResponseUsage? = null,
)

data class ResponseUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
)
