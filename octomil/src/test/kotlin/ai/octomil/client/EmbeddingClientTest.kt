package ai.octomil.client

import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class EmbeddingClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: EmbeddingClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = EmbeddingClient(
            serverUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "test-key",
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ------------------------------------------------------------------
    // Response parsing
    // ------------------------------------------------------------------

    @Test
    fun `parses EmbeddingResponse correctly`() {
        val json = Json { ignoreUnknownKeys = true }
        val raw = """
            {
                "data": [{"embedding": [0.1, 0.2, 0.3], "index": 0}],
                "model": "nomic-embed-text",
                "usage": {"prompt_tokens": 5, "total_tokens": 5}
            }
        """.trimIndent()
        val parsed = json.decodeFromString<EmbeddingResponse>(raw)
        assertEquals(1, parsed.data.size)
        assertEquals(listOf(0.1, 0.2, 0.3), parsed.data[0].embedding)
        assertEquals("nomic-embed-text", parsed.model)
        assertEquals(5, parsed.usage.promptTokens)
        assertEquals(5, parsed.usage.totalTokens)
    }

    @Test
    fun `parses multiple embeddings`() {
        val json = Json { ignoreUnknownKeys = true }
        val raw = """
            {
                "data": [
                    {"embedding": [0.1, 0.2], "index": 0},
                    {"embedding": [0.3, 0.4], "index": 1}
                ],
                "model": "nomic-embed-text",
                "usage": {"prompt_tokens": 10, "total_tokens": 10}
            }
        """.trimIndent()
        val parsed = json.decodeFromString<EmbeddingResponse>(raw)
        assertEquals(2, parsed.data.size)
        assertEquals(listOf(0.1, 0.2), parsed.data[0].embedding)
        assertEquals(listOf(0.3, 0.4), parsed.data[1].embedding)
    }

    // ------------------------------------------------------------------
    // Single string embed via MockWebServer
    // ------------------------------------------------------------------

    @Test
    fun `embed single string returns correct result`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                        "data": [{"embedding": [0.1, 0.2, 0.3], "index": 0}],
                        "model": "nomic-embed-text",
                        "usage": {"prompt_tokens": 5, "total_tokens": 5}
                    }
                """.trimIndent())
        )

        val result = client.embed("nomic-embed-text", input = "hello world")

        assertEquals(1, result.embeddings.size)
        assertEquals(listOf(0.1, 0.2, 0.3), result.embeddings[0])
        assertEquals("nomic-embed-text", result.model)
        assertEquals(5, result.usage.promptTokens)
        assertEquals(5, result.usage.totalTokens)
    }

    @Test
    fun `embed multiple strings returns correct result`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                        "data": [
                            {"embedding": [0.1, 0.2], "index": 0},
                            {"embedding": [0.3, 0.4], "index": 1}
                        ],
                        "model": "nomic-embed-text",
                        "usage": {"prompt_tokens": 10, "total_tokens": 10}
                    }
                """.trimIndent())
        )

        val result = client.embed("nomic-embed-text", input = listOf("hello", "world"))

        assertEquals(2, result.embeddings.size)
        assertEquals(listOf(0.1, 0.2), result.embeddings[0])
        assertEquals(listOf(0.3, 0.4), result.embeddings[1])
    }

    // ------------------------------------------------------------------
    // Request format
    // ------------------------------------------------------------------

    @Test
    fun `sends correct request format`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                        "data": [{"embedding": [0.1], "index": 0}],
                        "model": "nomic-embed-text",
                        "usage": {"prompt_tokens": 1, "total_tokens": 1}
                    }
                """.trimIndent())
        )

        client.embed("nomic-embed-text", input = "test")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assert(request.path!!.endsWith("/api/v1/embeddings"))
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))

        val body = request.body.readUtf8()
        assert(body.contains("\"model_id\":\"nomic-embed-text\""))
        assert(body.contains("\"input\":\"test\""))
    }

    // ------------------------------------------------------------------
    // Error handling
    // ------------------------------------------------------------------

    @Test
    fun `throws on HTTP error`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("Unauthorized")
        )

        assertFailsWith<RuntimeException> {
            client.embed("model", input = "test")
        }
    }

    // ------------------------------------------------------------------
    // Data classes
    // ------------------------------------------------------------------

    @Test
    fun `EmbeddingUsage fields`() {
        val usage = EmbeddingUsage(promptTokens = 5, totalTokens = 5)
        assertEquals(5, usage.promptTokens)
        assertEquals(5, usage.totalTokens)
    }

    @Test
    fun `EmbeddingResult fields`() {
        val result = EmbeddingResult(
            embeddings = listOf(listOf(0.1, 0.2)),
            model = "nomic-embed-text",
            usage = EmbeddingUsage(promptTokens = 5, totalTokens = 5),
        )
        assertEquals(1, result.embeddings.size)
        assertEquals("nomic-embed-text", result.model)
        assertEquals(5, result.usage.promptTokens)
    }
}
