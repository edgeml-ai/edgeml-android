package ai.octomil.responses.tools

import ai.octomil.responses.InputItem
import ai.octomil.responses.OctomilResponses
import ai.octomil.responses.OutputItem
import ai.octomil.responses.ResponseRequest
import ai.octomil.responses.ResponseToolCall
import ai.octomil.responses.runtime.ModelRuntime
import ai.octomil.responses.runtime.RuntimeCapabilities
import ai.octomil.responses.runtime.RuntimeChunk
import ai.octomil.responses.runtime.RuntimeRequest
import ai.octomil.responses.runtime.RuntimeResponse
import ai.octomil.responses.runtime.RuntimeToolCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolRunnerTest {

    @Test
    fun `returns immediately when no tool calls`() = runTest {
        val runtime = sequentialRuntime(listOf(
            RuntimeResponse(text = "Hello world"),
        ))
        val responses = OctomilResponses(runtimeResolver = { runtime })
        val executor = CountingExecutor()
        val runner = ToolRunner(responses, executor)

        val response = runner.run(
            ResponseRequest(model = "test", input = listOf(InputItem.text("Hi")))
        )

        assertEquals("Hello world", (response.output.first() as OutputItem.Text).text)
        assertEquals(0, executor.callCount)
    }

    @Test
    fun `executes tool call and feeds result back`() = runTest {
        val runtime = sequentialRuntime(listOf(
            // First: model returns tool call
            RuntimeResponse(
                text = "",
                toolCalls = listOf(RuntimeToolCall(id = "call_1", name = "get_weather", arguments = "{\"city\":\"NYC\"}")),
            ),
            // Second: model returns text after seeing tool result
            RuntimeResponse(text = "It's 72\u00b0F in NYC"),
        ))
        val responses = OctomilResponses(runtimeResolver = { runtime })
        val executor = MapExecutor(mapOf("get_weather" to "72\u00b0F, sunny"))
        val runner = ToolRunner(responses, executor)

        val response = runner.run(
            ResponseRequest(model = "test", input = listOf(InputItem.text("What's the weather?")))
        )

        val text = response.output.filterIsInstance<OutputItem.Text>().joinToString("") { it.text }
        assertEquals("It's 72\u00b0F in NYC", text)
    }

    @Test
    fun `respects maxIterations`() = runTest {
        // Runtime always returns tool calls
        val runtime = object : ModelRuntime {
            override val capabilities = RuntimeCapabilities()
            override suspend fun run(request: RuntimeRequest) = RuntimeResponse(
                text = "",
                toolCalls = listOf(RuntimeToolCall(id = "call_1", name = "loop", arguments = "{}")),
            )
            override fun stream(request: RuntimeRequest): Flow<RuntimeChunk> = emptyFlow()
            override fun close() {}
        }
        val responses = OctomilResponses(runtimeResolver = { runtime })
        val executor = CountingExecutor()
        val runner = ToolRunner(responses, executor, maxIterations = 3)

        runner.run(ResponseRequest(model = "test", input = listOf(InputItem.text("Loop"))))

        // Should have been called 3 times (one per iteration) before giving up
        assertEquals(3, executor.callCount)
    }

    @Test
    fun `handles tool execution error gracefully`() = runTest {
        val runtime = sequentialRuntime(listOf(
            RuntimeResponse(
                text = "",
                toolCalls = listOf(RuntimeToolCall(id = "call_1", name = "failing_tool", arguments = "{}")),
            ),
            RuntimeResponse(text = "Sorry, that didn't work"),
        ))
        val responses = OctomilResponses(runtimeResolver = { runtime })
        val executor = object : ToolExecutor {
            override suspend fun execute(call: ResponseToolCall): ToolResult {
                throw RuntimeException("Network error")
            }
        }
        val runner = ToolRunner(responses, executor)

        val response = runner.run(
            ResponseRequest(model = "test", input = listOf(InputItem.text("Try this")))
        )

        val text = response.output.filterIsInstance<OutputItem.Text>().joinToString("") { it.text }
        assertEquals("Sorry, that didn't work", text)
    }

    // -- Helpers --

    private class CountingExecutor : ToolExecutor {
        var callCount = 0
        override suspend fun execute(call: ResponseToolCall): ToolResult {
            callCount++
            return ToolResult(toolCallId = call.id, content = "ok")
        }
    }

    private class MapExecutor(private val results: Map<String, String>) : ToolExecutor {
        override suspend fun execute(call: ResponseToolCall): ToolResult {
            val content = results[call.name] ?: "unknown"
            return ToolResult(toolCallId = call.id, content = content)
        }
    }

    private fun sequentialRuntime(responses: List<RuntimeResponse>): ModelRuntime {
        val iterator = responses.iterator()
        return object : ModelRuntime {
            override val capabilities = RuntimeCapabilities()
            override suspend fun run(request: RuntimeRequest): RuntimeResponse {
                return if (iterator.hasNext()) iterator.next() else RuntimeResponse(text = "")
            }
            override fun stream(request: RuntimeRequest): Flow<RuntimeChunk> = emptyFlow()
            override fun close() {}
        }
    }
}
