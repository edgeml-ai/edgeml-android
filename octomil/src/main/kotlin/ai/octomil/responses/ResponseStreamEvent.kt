package ai.octomil.responses

sealed interface ResponseStreamEvent {
    data class TextDelta(val delta: String) : ResponseStreamEvent

    data class ToolCallDelta(
        val index: Int,
        val id: String? = null,
        val name: String? = null,
        val argumentsDelta: String? = null,
    ) : ResponseStreamEvent

    data class Done(val response: Response) : ResponseStreamEvent

    data class Error(val error: Throwable) : ResponseStreamEvent
}
