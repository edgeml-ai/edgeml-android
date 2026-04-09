package ai.octomil.responses

data class Response(
    val id: String,
    val model: String,
    val output: List<OutputItem>,
    val finishReason: String,
    val usage: ResponseUsage? = null,
) {
    val outputText: String
        get() = output.filterIsInstance<OutputItem.Text>().joinToString("") { it.text }
}

data class ResponseUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
)
