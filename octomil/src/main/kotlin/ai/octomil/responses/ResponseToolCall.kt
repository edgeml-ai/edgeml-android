package ai.octomil.responses

import ai.octomil.chat.FunctionCall
import ai.octomil.chat.ToolCall

data class ResponseToolCall(
    val id: String,
    val name: String,
    val arguments: String,
) {
    fun toLegacyToolCall(): ToolCall = ToolCall(
        id = id,
        function = FunctionCall(name = name, arguments = arguments),
    )

    companion object {
        fun fromLegacy(toolCall: ToolCall): ResponseToolCall = ResponseToolCall(
            id = toolCall.id,
            name = toolCall.function.name,
            arguments = toolCall.function.arguments,
        )
    }
}
