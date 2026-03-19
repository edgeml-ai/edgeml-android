package ai.octomil.runtime.core

/** Canonical ChatML renderer for text-only engines. */
object ChatMLRenderer {
    fun render(
        request: RuntimeRequest,
        toolChoice: String = "auto",
        specificToolName: String? = null,
    ): String {
        val sb = StringBuilder()

        // Tool definitions block
        val tools = request.toolDefinitions
        if (!tools.isNullOrEmpty() && toolChoice != "none") {
            sb.append(renderToolBlock(tools, toolChoice, specificToolName))
        }

        // Messages
        for (msg in request.messages) {
            sb.append(renderMessage(msg))
        }

        // Generation prompt
        sb.append("<|assistant|>\n")
        return sb.toString()
    }

    private fun renderToolBlock(tools: List<RuntimeToolDef>, toolChoice: String, specificToolName: String?): String {
        val sb = StringBuilder()
        sb.append("<|system|>\nYou have access to the following tools:\n\n")
        for (tool in tools) {
            sb.append("Function: ${tool.name}\n")
            sb.append("Description: ${tool.description}\n")
            if (tool.parametersSchema != null) {
                sb.append("Parameters: ${tool.parametersSchema}\n")
            }
            sb.append("\n")
        }
        sb.append("To use a tool, respond with ONLY this JSON and nothing else:\n")
        sb.append("{\"type\": \"tool_call\", \"name\": \"function_name\", \"arguments\": {...}}\n")
        sb.append("If you do not need a tool, respond with normal text.\n")
        when (toolChoice) {
            "required" -> sb.append("You MUST use one of the available tools.\n")
            "specific" -> specificToolName?.let { sb.append("You MUST use the tool: $it\n") }
        }
        sb.append("\n")
        return sb.toString()
    }

    private fun renderMessage(msg: RuntimeMessage): String {
        val sb = StringBuilder()
        sb.append("<|${msg.role.code}|>\n")
        var prevWasText = false
        for (part in msg.parts) {
            when (part) {
                is RuntimeContentPart.Text -> {
                    if (prevWasText) sb.append("\n")
                    sb.append(part.text)
                    prevWasText = true
                }
                is RuntimeContentPart.Image -> { sb.append("[image]"); prevWasText = false }
                is RuntimeContentPart.Audio -> { sb.append("[audio]"); prevWasText = false }
                is RuntimeContentPart.Video -> { sb.append("[video]"); prevWasText = false }
            }
        }
        sb.append("\n")
        return sb.toString()
    }
}
