package ai.octomil.responses

import ai.octomil.chat.Tool
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptFormatterTest {

    @Test
    fun `formats simple text input`() {
        val result = PromptFormatter.format(
            input = listOf(InputItem.text("Hello")),
        )
        assertTrue(result.contains("<|user|>\nHello\n"))
        assertTrue(result.endsWith("<|assistant|>\n"))
    }

    @Test
    fun `formats system message`() {
        val result = PromptFormatter.format(
            input = listOf(
                InputItem.system("You are helpful"),
                InputItem.text("Hi"),
            ),
        )
        assertTrue(result.contains("<|system|>\nYou are helpful\n"))
        assertTrue(result.contains("<|user|>\nHi\n"))
    }

    @Test
    fun `formats tool result`() {
        val result = PromptFormatter.format(
            input = listOf(
                InputItem.ToolResult(toolCallId = "call_1", content = "72\u00b0F"),
            ),
        )
        assertTrue(result.contains("<|tool|>\n72\u00b0F\n"))
    }

    @Test
    fun `formats assistant with tool calls`() {
        val result = PromptFormatter.format(
            input = listOf(
                InputItem.Assistant(
                    toolCalls = listOf(
                        ResponseToolCall(id = "call_1", name = "get_weather", arguments = "{\"city\":\"NYC\"}")
                    )
                ),
            ),
        )
        assertTrue(result.contains("<|assistant|>\n"))
        assertTrue(result.contains("get_weather"))
    }

    @Test
    fun `includes tool definitions when tools provided`() {
        val result = PromptFormatter.format(
            input = listOf(InputItem.text("What's the weather?")),
            tools = listOf(
                Tool.function("get_weather", "Get weather for a city"),
            ),
        )
        assertTrue(result.contains("Function: get_weather"))
        assertTrue(result.contains("Description: Get weather for a city"))
        assertTrue(result.contains("tool_call"))
    }

    @Test
    fun `skips tool definitions when toolChoice is None`() {
        val result = PromptFormatter.format(
            input = listOf(InputItem.text("Hello")),
            tools = listOf(Tool.function("get_weather", "Get weather")),
            toolChoice = ToolChoice.None,
        )
        assertTrue(!result.contains("Function: get_weather"))
    }

    @Test
    fun `adds required instruction for ToolChoice Required`() {
        val result = PromptFormatter.format(
            input = listOf(InputItem.text("Hello")),
            tools = listOf(Tool.function("get_weather", "Get weather")),
            toolChoice = ToolChoice.Required,
        )
        assertTrue(result.contains("MUST use one of the available tools"))
    }

    @Test
    fun `adds specific tool instruction for ToolChoice Specific`() {
        val result = PromptFormatter.format(
            input = listOf(InputItem.text("Hello")),
            tools = listOf(Tool.function("get_weather", "Get weather")),
            toolChoice = ToolChoice.Specific("get_weather"),
        )
        assertTrue(result.contains("MUST use the tool: get_weather"))
    }

    @Test
    fun `formats image content part as placeholder`() {
        val result = PromptFormatter.format(
            input = listOf(
                InputItem.User(content = listOf(
                    ContentPart.Text("What is this?"),
                    ContentPart.Image(data = "base64data", mediaType = "image/png"),
                )),
            ),
        )
        assertTrue(result.contains("What is this?"))
        assertTrue(result.contains("[image]"))
    }

    @Test
    fun `formats multi-turn conversation`() {
        val result = PromptFormatter.format(
            input = listOf(
                InputItem.system("You are a helpful assistant"),
                InputItem.text("Hello"),
                InputItem.Assistant(content = listOf(ContentPart.Text("Hi! How can I help?"))),
                InputItem.text("What is 2+2?"),
            ),
        )
        assertTrue(result.contains("<|system|>\nYou are a helpful assistant\n"))
        assertTrue(result.contains("<|user|>\nHello\n"))
        assertTrue(result.contains("<|assistant|>\nHi! How can I help?\n"))
        assertTrue(result.contains("<|user|>\nWhat is 2+2?\n"))
        assertTrue(result.endsWith("<|assistant|>\n"))
    }
}
