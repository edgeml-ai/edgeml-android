package ai.octomil.responses

import ai.octomil.chat.Tool

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
) {
    companion object {
        /** Create a simple text request. */
        fun text(model: String, text: String): ResponseRequest =
            ResponseRequest(model = model, input = listOf(InputItem.text(text)))
    }
}
