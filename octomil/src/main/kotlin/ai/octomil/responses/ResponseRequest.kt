package ai.octomil.responses

import ai.octomil.chat.Tool
import ai.octomil.generated.ModelCapability
import ai.octomil.generated.RoutingPolicy
import ai.octomil.manifest.ModelRef

data class ResponseRequest(
    val model: String,
    val input: List<InputItem>,
    val tools: List<Tool> = emptyList(),
    val toolChoice: ToolChoice = ToolChoice.Auto,
    val responseFormat: ResponseFormat = ResponseFormat.Text,
    val stream: Boolean = false,
    val maxOutputTokens: Int? = null,
    val temperature: Float? = null,
    val topP: Float? = null,
    val stop: List<String>? = null,
    val metadata: Map<String, String>? = null,
    /** System prompt shorthand -- prepended as a system message to input. */
    val instructions: String? = null,
    /** Previous response ID for conversation chaining. */
    val previousResponseId: String? = null,
    /** Capability-based or ID-based model reference. When set, takes priority over [model]. */
    val modelRef: ModelRef? = null,
    /** Per-request routing policy override (e.g. force cloud for a single request). */
    val routing: RoutingPolicy? = null,
) {
    companion object {
        /** Create a simple text request by model ID. */
        fun text(model: String, text: String): ResponseRequest =
            ResponseRequest(model = model, input = listOf(InputItem.text(text)))

        /** Create a request by capability instead of model ID. */
        fun forCapability(
            capability: ModelCapability,
            input: List<InputItem>,
            instructions: String? = null,
        ): ResponseRequest = ResponseRequest(
            model = "",
            input = input,
            instructions = instructions,
            modelRef = ModelRef.Capability(capability),
        )
    }
}
