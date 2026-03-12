package ai.octomil.responses

sealed interface OutputItem {
    data class Text(val text: String) : OutputItem
    data class ToolCallItem(val toolCall: ResponseToolCall) : OutputItem
    data class JsonOutput(val json: String) : OutputItem
}
