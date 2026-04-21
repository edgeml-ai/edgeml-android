package ai.octomil.responses

import ai.octomil.runtime.core.ModelRuntime
import ai.octomil.runtime.core.RuntimeCapabilities
import ai.octomil.runtime.core.RuntimeChunk
import ai.octomil.runtime.core.RuntimeRequest
import ai.octomil.runtime.core.RuntimeResponse
import ai.octomil.runtime.core.RuntimeToolCall
import ai.octomil.runtime.core.RuntimeToolCallDelta
import ai.octomil.runtime.core.RuntimeUsage
import ai.octomil.wrapper.TelemetryQueue
import ai.octomil.wrapper.TelemetrySender
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OctomilResponsesTest {

    @Test
    fun `create returns text response`() = runTest {
        val runtime = mockRuntime(RuntimeResponse(text = "Hello world", finishReason = "stop"))
        val responses = OctomilResponses(runtimeResolver = { runtime })

        val response = responses.create(
            ResponseRequest(model = "test", input = listOf(InputItem.text("Hi")))
        )

        assertEquals(1, response.output.size)
        val text = response.output.first() as OutputItem.Text
        assertEquals("Hello world", text.text)
        assertEquals("stop", response.finishReason)
    }

    @Test
    fun `create returns tool call response`() = runTest {
        val runtime = mockRuntime(
            RuntimeResponse(
                text = "",
                toolCalls = listOf(
                    RuntimeToolCall(id = "call_1", name = "get_weather", arguments = "{\"city\":\"NYC\"}")
                ),
                finishReason = "tool_calls",
            )
        )
        val responses = OctomilResponses(runtimeResolver = { runtime })

        val response = responses.create(
            ResponseRequest(model = "test", input = listOf(InputItem.text("Weather?")))
        )

        val toolCallItems = response.output.filterIsInstance<OutputItem.ToolCallItem>()
        assertEquals(1, toolCallItems.size)
        assertEquals("get_weather", toolCallItems[0].toolCall.name)
        assertEquals("tool_calls", response.finishReason)
    }

    @Test
    fun `stream emits text deltas and done`() = runTest {
        val runtime = streamingRuntime(listOf(
            RuntimeChunk(text = "Hello"),
            RuntimeChunk(text = " world"),
        ))
        val responses = OctomilResponses(runtimeResolver = { runtime })

        val events = responses.stream(
            ResponseRequest(model = "test", input = listOf(InputItem.text("Hi")))
        ).toList()

        val textDeltas = events.filterIsInstance<ResponseStreamEvent.TextDelta>()
        assertEquals(2, textDeltas.size)
        assertEquals("Hello", textDeltas[0].delta)
        assertEquals(" world", textDeltas[1].delta)

        val done = events.filterIsInstance<ResponseStreamEvent.Done>()
        assertEquals(1, done.size)
        assertEquals("stop", done[0].response.finishReason)
    }

    @Test
    fun `stream handles tool call deltas`() = runTest {
        val runtime = streamingRuntime(listOf(
            RuntimeChunk(
                toolCallDelta = RuntimeToolCallDelta(
                    index = 0, id = "call_1", name = "get_weather", argumentsDelta = "{\"city\":"
                )
            ),
            RuntimeChunk(
                toolCallDelta = RuntimeToolCallDelta(
                    index = 0, argumentsDelta = "\"NYC\"}"
                )
            ),
        ))
        val responses = OctomilResponses(runtimeResolver = { runtime })

        val events = responses.stream(
            ResponseRequest(model = "test", input = listOf(InputItem.text("Weather?")))
        ).toList()

        val done = events.filterIsInstance<ResponseStreamEvent.Done>().first()
        val toolCalls = done.response.output.filterIsInstance<OutputItem.ToolCallItem>()
        assertEquals(1, toolCalls.size)
        assertEquals("get_weather", toolCalls[0].toolCall.name)
        assertEquals("{\"city\":\"NYC\"}", toolCalls[0].toolCall.arguments)
        assertEquals("tool_calls", done.response.finishReason)
    }

    @Test
    fun `create includes usage data`() = runTest {
        val runtime = mockRuntime(
            RuntimeResponse(
                text = "result",
                usage = RuntimeUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15),
            )
        )
        val responses = OctomilResponses(runtimeResolver = { runtime })

        val response = responses.create(
            ResponseRequest(model = "test", input = listOf(InputItem.text("Hi")))
        )

        assertEquals(10, response.usage?.promptTokens)
        assertEquals(5, response.usage?.completionTokens)
        assertEquals(15, response.usage?.totalTokens)
    }

    @Test(expected = ai.octomil.errors.OctomilException::class)
    fun `create throws when no runtime found`() = runTest {
        val responses = OctomilResponses(runtimeResolver = { null })
        responses.create(ResponseRequest(model = "unknown", input = listOf(InputItem.text("Hi"))))
    }

    @Test
    fun `create emits route decision telemetry`() = runTest {
        val queue = TelemetryQueue(
            batchSize = 100,
            flushIntervalMs = 0,
            persistDir = null,
            sender = TelemetrySender { },
        )
        queue.start()
        try {
            val runtime = mockRuntime(RuntimeResponse(text = "Hello world", finishReason = "stop"))
            val responses = OctomilResponses(runtimeResolver = { runtime })

            responses.create(
                ResponseRequest(model = "test", input = listOf(InputItem.text("Hi")))
            )

            val routeEvents = queue.bufferedV2Events.filter { it.name == "route.decision" }
            assertEquals(1, routeEvents.size)
            val attrs = routeEvents[0].attributes
            assertEquals("responses", attrs["route.capability"]?.content)
            assertEquals("local", attrs["route.final_locality"]?.content)
            assertEquals("1", attrs["route.candidate_attempts"]?.content)
        } finally {
            queue.close()
        }
    }

    @Test
    fun `stream emits route decision telemetry`() = runTest {
        val queue = TelemetryQueue(
            batchSize = 100,
            flushIntervalMs = 0,
            persistDir = null,
            sender = TelemetrySender { },
        )
        queue.start()
        try {
            val runtime = streamingRuntime(listOf(
                RuntimeChunk(text = "Hello"),
                RuntimeChunk(text = " world"),
            ))
            val responses = OctomilResponses(runtimeResolver = { runtime })

            responses.stream(
                ResponseRequest(model = "test", input = listOf(InputItem.text("Hi")))
            ).toList()

            val routeEvents = queue.bufferedV2Events.filter { it.name == "route.decision" }
            assertEquals(1, routeEvents.size)
            val attrs = routeEvents[0].attributes
            assertEquals("responses", attrs["route.capability"]?.content)
            assertEquals("local", attrs["route.final_locality"]?.content)
            assertEquals("1", attrs["route.candidate_attempts"]?.content)
        } finally {
            queue.close()
        }
    }

    private fun mockRuntime(response: RuntimeResponse): ModelRuntime = object : ModelRuntime {
        override val capabilities = RuntimeCapabilities()
        override suspend fun run(request: RuntimeRequest) = response
        override fun stream(request: RuntimeRequest): Flow<RuntimeChunk> = flow {}
        override fun close() {}
    }

    private fun streamingRuntime(chunks: List<RuntimeChunk>): ModelRuntime = object : ModelRuntime {
        override val capabilities = RuntimeCapabilities()
        override suspend fun run(request: RuntimeRequest) = RuntimeResponse(text = "")
        override fun stream(request: RuntimeRequest): Flow<RuntimeChunk> = flow {
            chunks.forEach { emit(it) }
        }
        override fun close() {}
    }
}
