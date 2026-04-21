package ai.octomil.responses

data class Response(
    val id: String,
    val model: String,
    val output: List<OutputItem>,
    val finishReason: String,
    val usage: ResponseUsage? = null,
    /** Privacy-safe route metadata describing how this request was routed. */
    val routeMetadata: ai.octomil.runtime.planner.RouteMetadata? = null,
) {
    val outputText: String
        get() = output.filterIsInstance<OutputItem.Text>().joinToString("") { it.text }
}

data class ResponseUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
)
