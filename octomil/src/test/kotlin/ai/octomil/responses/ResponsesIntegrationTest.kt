package ai.octomil.responses

import ai.octomil.runtime.core.ModelRuntime
import ai.octomil.runtime.core.ModelRuntimeRegistry
import ai.octomil.runtime.core.RuntimeCapabilities
import ai.octomil.runtime.core.RuntimeChunk
import ai.octomil.runtime.core.RuntimeRequest
import ai.octomil.runtime.core.RuntimeResponse
import ai.octomil.runtime.core.RuntimeUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsesIntegrationTest {

    @After
    fun tearDown() {
        ModelRuntimeRegistry.clear()
    }

    @Test
    fun `create with registered default factory`() = runTest {
        ModelRuntimeRegistry.defaultFactory = { _ ->
            mockRuntime(RuntimeResponse(text = "Default factory response", finishReason = "stop"))
        }
        val responses = OctomilResponses()

        val response = responses.create(
            ResponseRequest(model = "test-model", input = listOf(InputItem.text("Hello")))
        )

        assertEquals(1, response.output.size)
        val text = response.output.first() as OutputItem.Text
        assertEquals("Default factory response", text.text)
        assertEquals("stop", response.finishReason)
    }

    @Test
    fun `stream with registered default factory`() = runTest {
        ModelRuntimeRegistry.defaultFactory = { _ ->
            streamingRuntime(listOf(
                RuntimeChunk(text = "Streaming "),
                RuntimeChunk(text = "response"),
            ))
        }
        val responses = OctomilResponses()

        val events = responses.stream(
            ResponseRequest(model = "test-model", input = listOf(InputItem.text("Hello")))
        ).toList()

        val textDeltas = events.filterIsInstance<ResponseStreamEvent.TextDelta>()
        assertEquals(2, textDeltas.size)
        assertEquals("Streaming ", textDeltas[0].delta)
        assertEquals("response", textDeltas[1].delta)

        val done = events.filterIsInstance<ResponseStreamEvent.Done>()
        assertEquals(1, done.size)
        assertEquals("Streaming response", (done[0].response.output.first() as OutputItem.Text).text)
    }

    @Test
    fun `create with string shorthand`() = runTest {
        val runtime = mockRuntime(RuntimeResponse(text = "Shorthand works", finishReason = "stop"))
        val responses = OctomilResponses(runtimeResolver = { runtime })

        val request = ResponseRequest.text("test-model", "Hello")
        val response = responses.create(request)

        assertEquals("Shorthand works", (response.output.first() as OutputItem.Text).text)
    }

    @Test
    fun `create with instructions prepends system message`() = runTest {
        var capturedPrompt = ""
        val runtime = object : ModelRuntime {
            override val capabilities = RuntimeCapabilities()
            override suspend fun run(request: RuntimeRequest): RuntimeResponse {
                capturedPrompt = request.prompt
                return RuntimeResponse(text = "OK", finishReason = "stop")
            }
            override fun stream(request: RuntimeRequest): Flow<RuntimeChunk> = flow {}
            override fun close() {}
        }
        val responses = OctomilResponses(runtimeResolver = { runtime })

        responses.create(
            ResponseRequest(
                model = "test",
                input = listOf(InputItem.text("Tell me a joke")),
                instructions = "You are a comedian",
            )
        )

        assertTrue(
            "Prompt should contain system instruction",
            capturedPrompt.contains("You are a comedian")
        )
        assertTrue(
            "System instruction should appear before user message",
            capturedPrompt.indexOf("You are a comedian") < capturedPrompt.indexOf("Tell me a joke")
        )
    }

    @Test
    fun `create with previousResponseId chains conversation`() = runTest {
        var callCount = 0
        var capturedPrompt = ""
        val runtime = object : ModelRuntime {
            override val capabilities = RuntimeCapabilities()
            override suspend fun run(request: RuntimeRequest): RuntimeResponse {
                callCount++
                capturedPrompt = request.prompt
                return when (callCount) {
                    1 -> RuntimeResponse(text = "I am a helpful assistant", finishReason = "stop")
                    else -> RuntimeResponse(text = "Chained response", finishReason = "stop")
                }
            }
            override fun stream(request: RuntimeRequest): Flow<RuntimeChunk> = flow {}
            override fun close() {}
        }
        val responses = OctomilResponses(runtimeResolver = { runtime })

        val first = responses.create(
            ResponseRequest(model = "test", input = listOf(InputItem.text("Who are you?")))
        )

        val second = responses.create(
            ResponseRequest(
                model = "test",
                input = listOf(InputItem.text("Tell me more")),
                previousResponseId = first.id,
            )
        )

        assertEquals("Chained response", (second.output.first() as OutputItem.Text).text)
        assertTrue(
            "Second prompt should contain the first assistant response",
            capturedPrompt.contains("I am a helpful assistant")
        )
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
