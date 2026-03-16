package ai.octomil.responses

import ai.octomil.chat.Tool

object PromptFormatter {

    fun format(
        input: List<InputItem>,
        tools: List<Tool> = emptyList(),
        toolChoice: ToolChoice = ToolChoice.Auto,
    ): String {
        val sb = StringBuilder()

        // Add tool definitions as a system prompt if present
        if (tools.isNotEmpty() && toolChoice !is ToolChoice.None) {
            sb.append("<|system|>\nYou have access to the following tools:\n\n")
            for (tool in tools) {
                sb.append("Function: ${tool.function.name}\n")
                sb.append("Description: ${tool.function.description}\n")
                if (tool.function.parameters != null) {
                    sb.append("Parameters: ${tool.function.parameters}\n")
                }
                sb.append("\n")
            }
            sb.append("To use a tool, respond with JSON: {\"tool_call\": {\"name\": \"function_name\", \"arguments\": {...}}}\n")

            when (toolChoice) {
                is ToolChoice.Required -> sb.append("You MUST use one of the available tools.\n")
                is ToolChoice.Specific -> sb.append("You MUST use the tool: ${toolChoice.name}\n")
                else -> {}
            }
            sb.append("\n")
        }

        // Format each input item
        for (item in input) {
            when (item) {
                is InputItem.System -> {
                    sb.append("<|system|>\n${item.content}\n")
                }
                is InputItem.User -> {
                    sb.append("<|user|>\n")
                    for (part in item.content) {
                        when (part) {
                            is ContentPart.Text -> sb.append(part.text)
                            is ContentPart.Image -> sb.append("[image]")
                            is ContentPart.Audio -> sb.append("[audio]")
                            is ContentPart.Video -> sb.append("[video]")
                            is ContentPart.File -> sb.append("[file: ${part.filename ?: "attachment"}]")
                        }
                    }
                    sb.append("\n")
                }
                is InputItem.Assistant -> {
                    sb.append("<|assistant|>\n")
                    item.content?.forEach { part ->
                        when (part) {
                            is ContentPart.Text -> sb.append(part.text)
                            else -> {}
                        }
                    }
                    item.toolCalls?.forEach { call ->
                        sb.append("{\"tool_call\": {\"name\": \"${call.name}\", \"arguments\": ${call.arguments}}}")
                    }
                    sb.append("\n")
                }
                is InputItem.ToolResult -> {
                    sb.append("<|tool|>\n${item.content}\n")
                }
            }
        }

        sb.append("<|assistant|>\n")
        return sb.toString()
    }
}
