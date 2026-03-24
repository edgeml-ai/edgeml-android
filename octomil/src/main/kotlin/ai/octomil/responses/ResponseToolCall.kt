package ai.octomil.responses

import ai.octomil.chat.FunctionCall
import ai.octomil.chat.LegacyToolCall

data class ResponseToolCall(
    val id: String,
    val name: String,
    val arguments: String,
) {
    fun toLegacyToolCall(): LegacyToolCall = LegacyToolCall(
        id = id,
        function = FunctionCall(name = name, arguments = arguments),
    )

    companion object {
        fun fromLegacy(toolCall: LegacyToolCall): ResponseToolCall = ResponseToolCall(
            id = toolCall.id,
            name = toolCall.function.name,
            arguments = toolCall.function.arguments,
        )
    }
}
