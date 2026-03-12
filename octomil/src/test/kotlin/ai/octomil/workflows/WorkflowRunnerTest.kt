package ai.octomil.workflows

import ai.octomil.chat.Tool
import ai.octomil.responses.InputItem
import ai.octomil.responses.OctomilResponses
import ai.octomil.responses.OutputItem
import ai.octomil.responses.ResponseToolCall
import ai.octomil.responses.runtime.ModelRuntime
import ai.octomil.responses.runtime.RuntimeCapabilities
import ai.octomil.responses.runtime.RuntimeChunk
import ai.octomil.responses.runtime.RuntimeRequest
import ai.octomil.responses.runtime.RuntimeResponse
import ai.octomil.responses.runtime.RuntimeToolCall
import ai.octomil.responses.tools.ToolExecutor
import ai.octomil.responses.tools.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowRunnerTest {

    @Test
    fun `single inference step`() = runTest {
        val runtime = stubRuntime("Summarized text")
        val responses = OctomilResponses(runtimeResolver = { runtime })
        val runner = WorkflowRunner(responses)

        val workflow = Workflow(
            name = "summarize",
            steps = listOf(
                WorkflowStep.Inference(model = "test", instructions = "Summarize"),
            ),
        )

        val result = runner.run(workflow, "Long article text")

        assertEquals(1, result.outputs.size)
        val text = result.outputs[0].output
            .filterIsInstance<OutputItem.Text>()
            .joinToString("") { it.text }
        assertEquals("Summarized text", text)
        assertTrue(result.totalLatencyMs >= 0)
    }

    @Test
    fun `multi-step pipeline inference then transform then inference`() = runTest {
        var callCount = 0
        val runtime = object : ModelRuntime {
            override val capabilities = RuntimeCapabilities()
            override suspend fun run(request: RuntimeRequest): RuntimeResponse {
                callCount++
                return when (callCount) {
                    1 -> RuntimeResponse(text = "Step 1 output", finishReason = "stop")
                    else -> RuntimeResponse(text = "Step 3 output", finishReason = "stop")
                }
            }
            override fun stream(request: RuntimeRequest): Flow<RuntimeChunk> = emptyFlow()
            override fun close() {}
        }
        val responses = OctomilResponses(runtimeResolver = { runtime })
        val runner = WorkflowRunner(responses)

        val workflow = Workflow(
            name = "multi-step",
            steps = listOf(
                WorkflowStep.Inference(model = "test"),
                WorkflowStep.Transform(name = "uppercase") { it.uppercase() },
                WorkflowStep.Inference(model = "test"),
            ),
        )

        val result = runner.run(workflow, "input text")

        assertEquals(2, result.outputs.size)
        val finalText = result.outputs[1].output
            .filterIsInstance<OutputItem.Text>()
            .joinToString("") { it.text }
        assertEquals("Step 3 output", finalText)
    }

    @Test
    fun `tool round step`() = runTest {
        var callCount = 0
        val runtime = object : ModelRuntime {
            override val capabilities = RuntimeCapabilities()
            override suspend fun run(request: RuntimeRequest): RuntimeResponse {
                callCount++
                return when (callCount) {
                    1 -> RuntimeResponse(
                        text = "",
                        toolCalls = listOf(
                            RuntimeToolCall(id = "call_1", name = "lookup", arguments = "{\"q\":\"test\"}")
                        ),
                        finishReason = "tool_calls",
                    )
                    else -> RuntimeResponse(text = "Final answer from tool", finishReason = "stop")
                }
            }
            override fun stream(request: RuntimeRequest): Flow<RuntimeChunk> = emptyFlow()
            override fun close() {}
        }
        val responses = OctomilResponses(runtimeResolver = { runtime })
        val executor = object : ToolExecutor {
            override suspend fun execute(call: ResponseToolCall): ToolResult {
                return ToolResult(toolCallId = call.id, content = "tool result data")
            }
        }
        val runner = WorkflowRunner(responses, executor)

        val tool = Tool.function(
            name = "lookup",
            description = "Look up data",
            parameters = buildJsonObject { put("q", JsonPrimitive("string")) },
        )
        val workflow = Workflow(
            name = "tool-workflow",
            steps = listOf(
                WorkflowStep.ToolRound(tools = listOf(tool), model = "test", maxIterations = 3),
            ),
        )

        val result = runner.run(workflow, "Find info")

        assertEquals(1, result.outputs.size)
        val text = result.outputs[0].output
            .filterIsInstance<OutputItem.Text>()
            .joinToString("") { it.text }
        assertEquals("Final answer from tool", text)
    }

    @Test
    fun `empty workflow returns empty result`() = runTest {
        val runtime = stubRuntime("unused")
        val responses = OctomilResponses(runtimeResolver = { runtime })
        val runner = WorkflowRunner(responses)

        val workflow = Workflow(name = "empty", steps = emptyList())
        val result = runner.run(workflow, "input")

        assertEquals(0, result.outputs.size)
        assertTrue(result.totalLatencyMs >= 0)
    }

    private fun stubRuntime(text: String): ModelRuntime = object : ModelRuntime {
        override val capabilities = RuntimeCapabilities()
        override suspend fun run(request: RuntimeRequest) =
            RuntimeResponse(text = text, finishReason = "stop")
        override fun stream(request: RuntimeRequest): Flow<RuntimeChunk> = emptyFlow()
        override fun close() {}
    }
}
