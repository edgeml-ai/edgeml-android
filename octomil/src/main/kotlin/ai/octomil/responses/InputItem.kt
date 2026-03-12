package ai.octomil.responses

sealed interface InputItem {
    data class System(val content: String) : InputItem

    data class User(val content: List<ContentPart>) : InputItem {
        companion object {
            fun text(value: String) = User(content = listOf(ContentPart.Text(value)))
        }
    }

    data class Assistant(
        val content: List<ContentPart>? = null,
        val toolCalls: List<ResponseToolCall>? = null,
    ) : InputItem

    data class ToolResult(
        val toolCallId: String,
        val content: String,
    ) : InputItem

    companion object {
        fun text(value: String) = User.text(value)
        fun system(value: String) = System(value)
    }
}
