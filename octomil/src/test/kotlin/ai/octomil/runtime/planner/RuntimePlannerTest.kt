package ai.octomil.runtime.planner

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuntimePlannerTest {

    private lateinit var context: Context
    private lateinit var prefs: FakePlannerPrefs
    private lateinit var store: RuntimePlannerStore
    private lateinit var server: MockWebServer
    private lateinit var client: RuntimePlannerClient

    private val testDevice = DeviceRuntimeProfile(
        sdk = "android",
        sdkVersion = "1.0.0",
        platform = "Android",
        arch = "arm64-v8a",
        osVersion = "14",
        apiLevel = 34,
        chip = "Snapdragon 8 Gen 3",
        deviceModel = "Google Pixel 8",
        ramTotalBytes = 8_589_934_592L,
        accelerators = listOf("nnapi", "gpu"),
        installedRuntimes = listOf(
            InstalledRuntime(
                engine = "tflite",
                available = true,
                metadata = mapOf("models" to "gemma-2b", "capabilities" to "text"),
            ),
            InstalledRuntime(engine = "llama_cpp", available = true),
        ),
    )

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        prefs = FakePlannerPrefs()
        store = RuntimePlannerStore(prefs)
        server = MockWebServer()
        server.start()
        client = RuntimePlannerClient(
            baseUrl = server.url("/").toString(),
            apiKey = "test-key",
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun planner(
        client: RuntimePlannerClient? = this.client,
        profileCollector: (() -> DeviceRuntimeProfile)? = { testDevice },
    ) = RuntimePlanner(
        context = context,
        store = store,
        client = client,
        profileCollector = profileCollector,
    )

    // =========================================================================
    // Server plan resolution
    // =========================================================================

    @Test
    fun `resolve returns server plan when available`() {
        enqueueServerPlan(
            model = "gemma-2b",
            engine = "llama_cpp",
            locality = "local",
        )

        val result = planner().resolve(
            model = "gemma-2b",
            capability = "text",
        )

        assertEquals("local", result.locality)
        assertEquals("llama.cpp", result.engine)
        assertEquals("server_plan", result.source)
    }

    @Test
    fun `resolve skips engine not in installed runtimes`() {
        enqueueServerPlan(
            model = "gemma-2b",
            engine = "executorch", // Not in testDevice.installedRuntimes
            locality = "local",
        )

        val result = planner().resolve(
            model = "gemma-2b",
            capability = "text",
        )

        // Should fall through to local engine selection since executorch is not installed
        // The first installed engine (tflite) should be selected
        assertEquals("local", result.locality)
    }

    @Test
    fun `resolve accepts cloud candidate from server`() {
        enqueueServerPlan(
            model = "gpt-4",
            engine = null,
            locality = "cloud",
        )

        val result = planner().resolve(
            model = "gpt-4",
            capability = "text",
        )

        assertEquals("cloud", result.locality)
        assertEquals("server_plan", result.source)
    }

    @Test
    fun `resolve uses fallback candidate when primary skipped`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""
                    {
                        "model": "test",
                        "capability": "text",
                        "policy": "local_first",
                        "candidates": [
                            {
                                "locality": "local",
                                "priority": 1,
                                "confidence": 0.9,
                                "reason": "best",
                                "engine": "executorch"
                            }
                        ],
                        "fallback_candidates": [
                            {
                                "locality": "local",
                                "priority": 2,
                                "confidence": 0.5,
                                "reason": "fallback",
                                "engine": "tflite"
                            }
                        ]
                    }
                """)
        )

        val result = planner().resolve("test", "text")

        assertEquals("local", result.locality)
        assertEquals("tflite", result.engine)
        assertTrue(result.reason.contains("fallback"))
    }

    // =========================================================================
    // Cached plan
    // =========================================================================

    @Test
    fun `resolve returns cached plan without network call`() {
        // Pre-populate cache
        val cacheKey = RuntimePlannerStore.makeCacheKey(
            model = "gemma-2b",
            capability = "text",
            policy = "local_first",
            sdkVersion = testDevice.sdkVersion,
            platform = testDevice.platform,
            arch = testDevice.arch,
            chip = testDevice.chip,
            installedHash = RuntimePlannerStore.installedRuntimesHash(testDevice.installedRuntimes),
        )
        store.putPlan(
            cacheKey,
            RuntimePlanResponse(
                model = "gemma-2b",
                capability = "text",
                policy = "local_first",
                candidates = listOf(
                    RuntimeCandidatePlan(
                        locality = "local",
                        engine = "llama_cpp",
                        reason = "cached",
                    ),
                ),
            ),
        )

        val result = planner().resolve(
            model = "gemma-2b",
            capability = "text",
            routingPolicy = "local_first",
        )

        assertEquals("local", result.locality)
        assertEquals("llama.cpp", result.engine)
        assertEquals("cache", result.source)
        // No server requests should have been made
        assertEquals(0, server.requestCount)
    }

    // =========================================================================
    // Cached benchmark
    // =========================================================================

    @Test
    fun `resolve returns cached benchmark when no server plan`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val bmKey = RuntimePlannerStore.makeCacheKey(
            model = "test",
            capability = "text",
            policy = "local_first",
            sdkVersion = testDevice.sdkVersion,
            platform = testDevice.platform,
            arch = testDevice.arch,
            chip = testDevice.chip,
            installedHash = RuntimePlannerStore.installedRuntimesHash(testDevice.installedRuntimes),
        )
        store.putBenchmark(bmKey, CachedBenchmark("test", "text", "tflite", 20.0))

        val result = planner().resolve("test", "text")

        assertEquals("local", result.locality)
        assertEquals("tflite", result.engine)
        assertEquals("cache", result.source)
    }

    // =========================================================================
    // Privacy policy
    // =========================================================================

    @Test
    fun `private policy skips server plan fetch`() {
        val result = planner().resolve(
            model = "gemma-2b",
            capability = "text",
            routingPolicy = "private",
        )

        // No server requests should have been made
        assertEquals(0, server.requestCount)
        assertEquals("local", result.locality)
    }

    @Test
    fun `private policy skips telemetry upload`() {
        val result = planner().resolve(
            model = "gemma-2b",
            capability = "text",
            routingPolicy = "private",
        )

        // Only the plan fetch is skipped; benchmark selection still happens locally
        // but telemetry upload is skipped. Total server requests should be 0.
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `private policy falls back to local when no engine available`() {
        val deviceNoEngines = testDevice.copy(installedRuntimes = emptyList())

        val result = planner(profileCollector = { deviceNoEngines }).resolve(
            model = "gemma-2b",
            capability = "text",
            routingPolicy = "private",
        )

        assertEquals("local", result.locality)
        assertEquals("fallback", result.source)
        assertNull(result.engine)
    }

    // =========================================================================
    // Cloud-only policy
    // =========================================================================

    @Test
    fun `cloud_only policy returns cloud without local benchmarking`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = planner().resolve(
            model = "gemma-2b",
            capability = "text",
            routingPolicy = "cloud_only",
        )

        assertEquals("cloud", result.locality)
        assertEquals("fallback", result.source)
        assertTrue(result.reason.contains("cloud_only"))
    }

    // =========================================================================
    // Offline operation
    // =========================================================================

    @Test
    fun `allowNetwork false skips server fetch`() {
        val result = planner().resolve(
            model = "gemma-2b",
            capability = "text",
            allowNetwork = false,
        )

        assertEquals(0, server.requestCount)
        assertEquals("local", result.locality)
    }

    // =========================================================================
    // Local engine selection
    // =========================================================================

    @Test
    fun `resolve selects first available local engine when server unavailable`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = planner().resolve(
            model = "gemma-2b",
            capability = "text",
        )

        assertEquals("local", result.locality)
        assertNotNull(result.engine)
        // Should pick first installed runtime (tflite)
        assertEquals("tflite", result.engine)
        assertEquals("local_default", result.source)
    }

    @Test
    fun `resolve falls back to cloud when no local engines available`() {
        val deviceNoEngines = testDevice.copy(installedRuntimes = emptyList())
        server.enqueue(MockResponse().setResponseCode(500))

        val result = planner(profileCollector = { deviceNoEngines }).resolve(
            model = "gemma-2b",
            capability = "text",
        )

        assertEquals("cloud", result.locality)
        assertEquals("fallback", result.source)
    }

    @Test
    fun `local_only policy does not fall back to cloud`() {
        val deviceNoEngines = testDevice.copy(installedRuntimes = emptyList())
        server.enqueue(MockResponse().setResponseCode(500))

        val result = planner(profileCollector = { deviceNoEngines }).resolve(
            model = "gemma-2b",
            capability = "text",
            routingPolicy = "local_only",
        )

        assertEquals("local", result.locality)
        assertEquals("fallback", result.source)
        assertNull(result.engine)
    }

    // =========================================================================
    // Benchmark telemetry upload
    // =========================================================================

    @Test
    fun `recordBenchmark uploads benchmark telemetry`() {
        server.enqueue(MockResponse().setResponseCode(200))

        planner().recordBenchmark(
            model = "gemma-2b",
            capability = "text",
            engine = "llama_cpp",
            tokensPerSecond = 22.0,
            ttftMs = 100.0,
            memoryMb = 512.0,
        )

        assertEquals(1, server.requestCount)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"engine\":\"llama.cpp\""))
        assertTrue(body.contains("\"tokens_per_second\":22.0"))
    }

    @Test
    fun `recordBenchmark persists benchmark locally`() {
        planner(client = null).recordBenchmark(
            model = "gemma-2b",
            capability = "text",
            engine = "llama_cpp",
            tokensPerSecond = 31.0,
        )

        // Second resolve should find the cached benchmark
        val result = planner(client = null).resolve("gemma-2b", "text")
        assertEquals("cache", result.source)
        assertEquals("llama.cpp", result.engine)
    }

    // =========================================================================
    // Server plan caching
    // =========================================================================

    @Test
    fun `resolve caches server plan locally`() {
        enqueueServerPlan("test", "llama_cpp", "local")

        planner().resolve("test", "text")

        // Second resolve should use cache
        val result = planner().resolve("test", "text")
        assertEquals("cache", result.source)
        assertEquals("llama.cpp", result.engine)
        // Only 1 server request (the first plan fetch)
        assertEquals(1, server.requestCount)
    }

    // =========================================================================
    // resolveFromServerPlan internal
    // =========================================================================

    @Test
    fun `resolveFromServerPlan returns null when no candidates match`() {
        val plan = RuntimePlanResponse(
            model = "test",
            capability = "text",
            policy = "auto",
            candidates = listOf(
                RuntimeCandidatePlan(
                    locality = "local",
                    engine = "executorch", // Not installed
                ),
            ),
            fallbackCandidates = listOf(
                RuntimeCandidatePlan(
                    locality = "local",
                    engine = "samsung_one", // Not installed
                ),
            ),
        )
        val result = planner().resolveFromServerPlan(plan, testDevice, "server_plan")
        assertNull(result)
    }

    @Test
    fun `resolveFromServerPlan accepts candidate with null engine`() {
        val plan = RuntimePlanResponse(
            model = "test",
            capability = "text",
            policy = "auto",
            candidates = listOf(
                RuntimeCandidatePlan(
                    locality = "local",
                    engine = null, // Server says "use any local engine"
                    reason = "any local",
                ),
            ),
        )
        val result = planner().resolveFromServerPlan(plan, testDevice, "server_plan")
        assertNotNull(result)
        assertEquals("local", result.locality)
        assertNull(result.engine)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun enqueueServerPlan(model: String, engine: String?, locality: String) {
        val engineField = if (engine != null) "\"engine\": \"$engine\"," else ""
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""
                    {
                        "model": "$model",
                        "capability": "text",
                        "policy": "local_first",
                        "candidates": [
                            {
                                "locality": "$locality",
                                "priority": 1,
                                "confidence": 0.9,
                                $engineField
                                "reason": "server selected"
                            }
                        ],
                        "fallback_candidates": []
                    }
                """)
        )
    }
}
