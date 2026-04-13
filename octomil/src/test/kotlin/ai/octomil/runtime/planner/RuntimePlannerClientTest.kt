package ai.octomil.runtime.planner

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuntimePlannerClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: RuntimePlannerClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = RuntimePlannerClient(
            baseUrl = server.url("/").toString(),
            apiKey = "test-api-key",
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // =========================================================================
    // fetchPlan
    // =========================================================================

    @Test
    fun `fetchPlan returns plan on success`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                        "model": "gemma-2b",
                        "capability": "text",
                        "policy": "local_first",
                        "candidates": [
                            {
                                "locality": "local",
                                "priority": 1,
                                "confidence": 0.9,
                                "reason": "best match",
                                "engine": "llama_cpp",
                                "benchmark_required": false
                            }
                        ],
                        "fallback_candidates": [],
                        "plan_ttl_seconds": 86400,
                        "server_generated_at": "2026-04-12T00:00:00Z"
                    }
                """)
        )

        val request = RuntimePlanRequest(
            model = "gemma-2b",
            capability = "text",
            device = testProfile(),
        )
        val plan = client.fetchPlan(request)

        assertNotNull(plan)
        assertEquals("gemma-2b", plan.model)
        assertEquals("text", plan.capability)
        assertEquals(1, plan.candidates.size)
        assertEquals("llama_cpp", plan.candidates[0].engine)
        assertEquals(86400, plan.planTtlSeconds)
    }

    @Test
    fun `fetchPlan sends correct request`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"model":"test","capability":"text","policy":"auto","candidates":[]}""")
        )

        val request = RuntimePlanRequest(
            model = "test-model",
            capability = "text",
            device = testProfile(),
            routingPolicy = "local_first",
            allowCloudFallback = true,
        )
        client.fetchPlan(request)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v2/runtime/plan", recorded.path)
        assertEquals("Bearer test-api-key", recorded.getHeader("Authorization"))
        assertEquals("application/json", recorded.getHeader("Accept"))

        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"model\":\"test-model\""))
        assertTrue(body.contains("\"capability\":\"text\""))
        assertTrue(body.contains("\"routing_policy\":\"local_first\""))
    }

    @Test
    fun `fetchPlan returns null on HTTP error`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val request = RuntimePlanRequest(
            model = "test",
            capability = "text",
            device = testProfile(),
        )
        val plan = client.fetchPlan(request)
        assertNull(plan)
    }

    @Test
    fun `fetchPlan returns null on 404`() {
        server.enqueue(MockResponse().setResponseCode(404))

        val plan = client.fetchPlan(
            RuntimePlanRequest("test", "text", testProfile())
        )
        assertNull(plan)
    }

    @Test
    fun `fetchPlan returns null on malformed JSON`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("not valid json")
        )

        val plan = client.fetchPlan(
            RuntimePlanRequest("test", "text", testProfile())
        )
        assertNull(plan)
    }

    @Test
    fun `fetchPlan returns null on empty response body`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("")
        )

        val plan = client.fetchPlan(
            RuntimePlanRequest("test", "text", testProfile())
        )
        assertNull(plan)
    }

    @Test
    fun `fetchPlan parses artifact in candidate`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""
                    {
                        "model": "llama-8b",
                        "capability": "text",
                        "policy": "auto",
                        "candidates": [
                            {
                                "locality": "local",
                                "priority": 1,
                                "confidence": 0.95,
                                "reason": "best for device",
                                "engine": "llama_cpp",
                                "artifact": {
                                    "model_id": "llama-8b",
                                    "format": "gguf",
                                    "quantization": "q4_k_m",
                                    "size_bytes": 4294967296,
                                    "digest": "sha256:abc"
                                }
                            }
                        ]
                    }
                """)
        )

        val plan = client.fetchPlan(
            RuntimePlanRequest("llama-8b", "text", testProfile())
        )
        assertNotNull(plan)
        val candidate = plan.candidates.first()
        assertNotNull(candidate.artifact)
        assertEquals("gguf", candidate.artifact!!.format)
        assertEquals("q4_k_m", candidate.artifact!!.quantization)
        assertEquals(4_294_967_296L, candidate.artifact!!.sizeBytes)
    }

    // =========================================================================
    // uploadBenchmark
    // =========================================================================

    @Test
    fun `uploadBenchmark returns true on success`() {
        server.enqueue(MockResponse().setResponseCode(200))

        val result = client.uploadBenchmark(testBenchmarkPayload())
        assertTrue(result)
    }

    @Test
    fun `uploadBenchmark sends to correct endpoint`() {
        server.enqueue(MockResponse().setResponseCode(200))

        client.uploadBenchmark(testBenchmarkPayload())

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v2/runtime/benchmarks", recorded.path)
        assertEquals("Bearer test-api-key", recorded.getHeader("Authorization"))
    }

    @Test
    fun `uploadBenchmark returns false on HTTP error`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = client.uploadBenchmark(testBenchmarkPayload())
        assertEquals(false, result)
    }

    @Test
    fun `uploadBenchmark returns false on 403`() {
        server.enqueue(MockResponse().setResponseCode(403))

        val result = client.uploadBenchmark(testBenchmarkPayload())
        assertEquals(false, result)
    }

    @Test
    fun `uploadBenchmark payload does not contain user data`() {
        server.enqueue(MockResponse().setResponseCode(200))

        client.uploadBenchmark(testBenchmarkPayload())

        val recorded = server.takeRequest()
        val body = recorded.body.readUtf8()
        assertTrue(!body.contains("prompt"))
        assertTrue(!body.contains("user_input"))
        assertTrue(!body.contains("file_path"))
        assertTrue(body.contains("\"model\":\"gemma-2b\""))
        assertTrue(body.contains("\"engine\":\"llama_cpp\""))
    }

    // =========================================================================
    // Auth
    // =========================================================================

    @Test
    fun `client without API key sends no auth header`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"model":"t","capability":"t","policy":"a","candidates":[]}"""
        ))

        val noAuthClient = RuntimePlannerClient(
            baseUrl = server.url("/").toString(),
            apiKey = null,
        )
        noAuthClient.fetchPlan(RuntimePlanRequest("t", "t", testProfile()))

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun testProfile() = DeviceRuntimeProfile(
        sdkVersion = "1.0.0",
        arch = "arm64-v8a",
    )

    private fun testBenchmarkPayload() = BenchmarkTelemetryPayload(
        model = "gemma-2b",
        capability = "text",
        engine = "llama_cpp",
        device = testProfile(),
        success = true,
        tokensPerSecond = 15.0,
    )
}
