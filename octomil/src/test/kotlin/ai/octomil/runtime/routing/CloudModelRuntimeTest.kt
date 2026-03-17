package ai.octomil.runtime.routing

import ai.octomil.errors.OctomilErrorCode
import ai.octomil.errors.OctomilException
import ai.octomil.runtime.core.RuntimeRequest
import ai.octomil.runtime.core.RuntimeToolDef
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class CloudModelRuntimeTest {

    private lateinit var server: MockWebServer
    private lateinit var runtime: CloudModelRuntime

    private val testClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        runtime = CloudModelRuntime(
            serverUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "test-key",
            model = "test-model",
            httpClient = testClient,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // =========================================================================
    // Non-streaming run()
    // =========================================================================

    @Test
    fun `run sends correct request and parses response`() = runTest {
        val responseJson = """
            {
                "id": "chatcmpl-123",
                "choices": [{
                    "index": 0,
                    "message": {
                        "role": "assistant",
                        "content": "Hello, world!"
                    },
                    "finish_reason": "stop"
                }],
                "usage": {
                    "prompt_tokens": 10,
                    "completion_tokens": 3,
                    "total_tokens": 13
                }
            }
        """.trimIndent()

        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(responseJson)
        )

        val request = RuntimeRequest(
            prompt = "Say hello",
            maxTokens = 100,
            temperature = 0.5f,
        )

        val response = runtime.run(request)

        assertEquals("Hello, world!", response.text)
        assertEquals("stop", response.finishReason)
        assertNotNull(response.usage)
        assertEquals(10, response.usage!!.promptTokens)
        assertEquals(3, response.usage!!.completionTokens)
        assertEquals(13, response.usage!!.totalTokens)
        assertNull(response.toolCalls)

        // Verify the HTTP request
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertTrue(recorded.path!!.endsWith("/v1/chat/completions"))
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
        assertTrue(recorded.getHeader("Content-Type")!!.startsWith("application/json"))

        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"model\":\"test-model\""))
        assertTrue(body.contains("\"stream\":false"))
        assertTrue(body.contains("Say hello"))
    }

    @Test
    fun `run parses tool calls`() = runTest {
        val responseJson = """
            {
                "choices": [{
                    "index": 0,
                    "message": {
                        "role": "assistant",
                        "content": null,
                        "tool_calls": [{
                            "id": "call_abc123",
                            "type": "function",
                            "function": {
                                "name": "get_weather",
                                "arguments": "{\"location\":\"NYC\"}"
                            }
                        }]
                    },
                    "finish_reason": "tool_calls"
                }],
                "usage": {
                    "prompt_tokens": 15,
                    "completion_tokens": 20,
                    "total_tokens": 35
                }
            }
        """.trimIndent()

        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(responseJson)
        )

        val response = runtime.run(stubRequest())

        assertEquals("", response.text)
        assertEquals("tool_calls", response.finishReason)
        assertNotNull(response.toolCalls)
        assertEquals(1, response.toolCalls!!.size)
        assertEquals("call_abc123", response.toolCalls!![0].id)
        assertEquals("get_weather", response.toolCalls!![0].name)
        assertEquals("{\"location\":\"NYC\"}", response.toolCalls!![0].arguments)
    }

    @Test
    fun `run throws OctomilException on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        try {
            runtime.run(stubRequest())
            fail("Expected OctomilException")
        } catch (e: OctomilException) {
            assertEquals(OctomilErrorCode.AUTHENTICATION_FAILED, e.errorCode)
            assertNotNull(e.message)
        }
    }

    @Test
    fun `run throws OctomilException on 403`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        try {
            runtime.run(stubRequest())
            fail("Expected OctomilException")
        } catch (e: OctomilException) {
            assertEquals(OctomilErrorCode.FORBIDDEN, e.errorCode)
        }
    }

    @Test
    fun `run throws OctomilException on 429`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))

        try {
            runtime.run(stubRequest())
            fail("Expected OctomilException")
        } catch (e: OctomilException) {
            assertEquals(OctomilErrorCode.RATE_LIMITED, e.errorCode)
            assertTrue(e.retryable)
        }
    }

    @Test
    fun `run throws OctomilException on 500`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        try {
            runtime.run(stubRequest())
            fail("Expected OctomilException")
        } catch (e: OctomilException) {
            assertEquals(OctomilErrorCode.SERVER_ERROR, e.errorCode)
            assertTrue(e.retryable)
        }
    }

    @Test
    fun `run handles empty choices`() = runTest {
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody("""{"choices":[],"usage":null}""")
        )

        val response = runtime.run(stubRequest())
        assertEquals("", response.text)
        assertEquals("stop", response.finishReason)
    }

    // =========================================================================
    // Streaming
    // =========================================================================

    @Test
    fun `stream emits chunks from SSE`() = runTest {
        val sseBody = buildString {
            appendLine("""data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"Hello"},"finish_reason":null}]}""")
            appendLine()
            appendLine("""data: {"choices":[{"index":0,"delta":{"content":" world"},"finish_reason":null}]}""")
            appendLine()
            appendLine("""data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}""")
            appendLine()
            appendLine("data: [DONE]")
            appendLine()
        }

        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "text/event-stream")
                .setBody(sseBody)
        )

        val chunks = runtime.stream(stubRequest()).toList()

        assertEquals(3, chunks.size)
        assertEquals("Hello", chunks[0].text)
        assertNull(chunks[0].finishReason)
        assertEquals(" world", chunks[1].text)
        assertNull(chunks[1].finishReason)
        assertEquals("stop", chunks[2].finishReason)
        assertNotNull(chunks[2].usage)
        assertEquals(7, chunks[2].usage!!.totalTokens)
    }

    @Test
    fun `stream emits tool call deltas`() = runTest {
        val sseBody = buildString {
            appendLine("""data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_weather","arguments":""}}]},"finish_reason":null}]}""")
            appendLine()
            appendLine("""data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"loc"}}]},"finish_reason":null}]}""")
            appendLine()
            appendLine("""data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ation\":\"NYC\"}"}}]},"finish_reason":null}]}""")
            appendLine()
            appendLine("""data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""")
            appendLine()
            appendLine("data: [DONE]")
        }

        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "text/event-stream")
                .setBody(sseBody)
        )

        val chunks = runtime.stream(stubRequest()).toList()

        assertEquals(4, chunks.size)
        assertNotNull(chunks[0].toolCallDelta)
        assertEquals("call_1", chunks[0].toolCallDelta!!.id)
        assertEquals("get_weather", chunks[0].toolCallDelta!!.name)
        assertEquals("{\"loc", chunks[1].toolCallDelta!!.argumentsDelta)
        assertEquals("ation\":\"NYC\"}", chunks[2].toolCallDelta!!.argumentsDelta)
        assertEquals("tool_calls", chunks[3].finishReason)
    }

    @Test
    fun `stream throws OctomilException on HTTP error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        try {
            runtime.stream(stubRequest()).toList()
            fail("Expected OctomilException")
        } catch (e: OctomilException) {
            assertEquals(OctomilErrorCode.MODEL_NOT_FOUND, e.errorCode)
        }
    }

    @Test
    fun `stream ignores non-data SSE lines`() = runTest {
        val sseBody = buildString {
            appendLine(": keep-alive comment")
            appendLine("event: ping")
            appendLine("""data: {"choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":null}]}""")
            appendLine()
            appendLine("id: 42")
            appendLine("data: [DONE]")
        }

        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "text/event-stream")
                .setBody(sseBody)
        )

        val chunks = runtime.stream(stubRequest()).toList()
        assertEquals(1, chunks.size)
        assertEquals("ok", chunks[0].text)
    }

    // =========================================================================
    // Request body building
    // =========================================================================

    @Test
    fun `buildRequestBody includes tools when present`() {
        val request = RuntimeRequest(
            prompt = "test",
            toolDefinitions = listOf(
                RuntimeToolDef(
                    name = "get_weather",
                    description = "Get weather for a location",
                    parametersSchema = """{"type":"object","properties":{"location":{"type":"string"}}}""",
                ),
            ),
        )

        val body = runtime.buildRequestBody(request, stream = false)
        assertTrue(body.contains("\"tools\""))
        assertTrue(body.contains("get_weather"))
        assertTrue(body.contains("Get weather for a location"))
    }

    @Test
    fun `buildRequestBody includes response_format for jsonSchema`() {
        val request = RuntimeRequest(
            prompt = "test",
            jsonSchema = """{"type":"object","properties":{"name":{"type":"string"}}}""",
        )

        val body = runtime.buildRequestBody(request, stream = false)
        assertTrue(body.contains("\"response_format\""))
        assertTrue(body.contains("\"json_schema\""))
    }

    @Test
    fun `buildRequestBody includes stop sequences`() {
        val request = RuntimeRequest(
            prompt = "test",
            stop = listOf("END", "STOP"),
        )

        val body = runtime.buildRequestBody(request, stream = false)
        assertTrue(body.contains("\"stop\""))
        assertTrue(body.contains("END"))
        assertTrue(body.contains("STOP"))
    }

    @Test
    fun `buildRequestBody sets stream flag correctly`() {
        val request = stubRequest()

        val nonStreaming = runtime.buildRequestBody(request, stream = false)
        assertTrue(nonStreaming.contains("\"stream\":false"))

        val streaming = runtime.buildRequestBody(request, stream = true)
        assertTrue(streaming.contains("\"stream\":true"))
    }

    // =========================================================================
    // SSE line parsing
    // =========================================================================

    @Test
    fun `parseSSELine returns null for empty line`() {
        assertNull(runtime.parseSSELine(""))
        assertNull(runtime.parseSSELine("   "))
    }

    @Test
    fun `parseSSELine returns null for non-data lines`() {
        assertNull(runtime.parseSSELine("event: message"))
        assertNull(runtime.parseSSELine(": comment"))
        assertNull(runtime.parseSSELine("id: 123"))
    }

    @Test
    fun `parseSSELine returns null for DONE marker`() {
        assertNull(runtime.parseSSELine("data: [DONE]"))
    }

    @Test
    fun `parseSSELine returns null for invalid JSON`() {
        assertNull(runtime.parseSSELine("data: not-json-at-all"))
    }

    @Test
    fun `parseSSELine parses content delta`() {
        val line = """data: {"choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":null}]}"""
        val chunk = runtime.parseSSELine(line)
        assertNotNull(chunk)
        assertEquals("Hi", chunk!!.text)
        assertNull(chunk.finishReason)
    }

    @Test
    fun `parseSSELine parses finish reason`() {
        val line = """data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""
        val chunk = runtime.parseSSELine(line)
        assertNotNull(chunk)
        assertEquals("stop", chunk!!.finishReason)
    }

    @Test
    fun `parseSSELine parses usage`() {
        val line = """data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"""
        val chunk = runtime.parseSSELine(line)
        assertNotNull(chunk)
        assertNotNull(chunk!!.usage)
        assertEquals(10, chunk.usage!!.promptTokens)
        assertEquals(5, chunk.usage!!.completionTokens)
        assertEquals(15, chunk.usage!!.totalTokens)
    }

    // =========================================================================
    // Non-streaming response parsing
    // =========================================================================

    @Test
    fun `parseNonStreamingResponse handles minimal response`() {
        val body = """{"choices":[{"index":0,"message":{"content":"ok"},"finish_reason":"stop"}]}"""
        val response = runtime.parseNonStreamingResponse(body)
        assertEquals("ok", response.text)
        assertEquals("stop", response.finishReason)
        assertNull(response.usage)
    }

    @Test
    fun `parseNonStreamingResponse handles null content`() {
        val body = """{"choices":[{"index":0,"message":{"content":null},"finish_reason":"stop"}]}"""
        val response = runtime.parseNonStreamingResponse(body)
        assertEquals("", response.text)
    }

    // =========================================================================
    // Capabilities
    // =========================================================================

    @Test
    fun `capabilities reports full cloud support`() {
        assertTrue(runtime.capabilities.supportsToolCalls)
        assertTrue(runtime.capabilities.supportsStructuredOutput)
        assertTrue(runtime.capabilities.supportsStreaming)
    }

    // =========================================================================
    // Constructor defaults
    // =========================================================================

    @Test
    fun `no apiKey omits Authorization header`() = runTest {
        val noKeyRuntime = CloudModelRuntime(
            serverUrl = server.url("/").toString().trimEnd('/'),
            apiKey = null,
            model = "test-model",
            httpClient = testClient,
        )

        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}""")
        )

        noKeyRuntime.run(stubRequest())

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun stubRequest() = RuntimeRequest(
        prompt = "test prompt",
        maxTokens = 100,
        temperature = 0.7f,
    )
}
