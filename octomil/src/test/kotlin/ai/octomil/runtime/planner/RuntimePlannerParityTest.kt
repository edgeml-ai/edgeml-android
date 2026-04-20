package ai.octomil.runtime.planner

import android.content.Context
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cross-SDK parity tests for the runtime planner.
 *
 * Validates that the Android SDK's planner types, policy names, route metadata,
 * and benchmark submission rules match the Python SDK. These tests are the
 * Android counterpart to the Python SDK's planner parity assertions.
 *
 * Groups:
 * 1. Policy name parity with Python SDK
 * 2. RouteMetadata field presence and mapping
 * 3. Private policy: no cloud candidates
 * 4. Cloud-only policy: no local candidates
 * 5. Benchmark submission rejects banned metadata keys
 * 6. RuntimeCandidatePlan field parity
 * 7. RuntimePlanResponse field parity
 */
class RuntimePlannerParityTest {

    private lateinit var context: Context
    private lateinit var prefs: FakePlannerPrefs
    private lateinit var store: RuntimePlannerStore

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        prefs = FakePlannerPrefs()
        store = RuntimePlannerStore(prefs)
    }

    // =========================================================================
    // 1. Policy name parity with Python SDK
    // =========================================================================

    @Test
    fun `policy names match Python SDK RoutingPolicy enum`() {
        // Python SDK defines: private, local_only, local_first, cloud_first,
        // cloud_only, performance_first, auto
        // The planner accepts these via RoutingPolicyNames (auto is implicit).
        assertTrue("private" in RoutingPolicyNames.ALL)
        assertTrue("local_only" in RoutingPolicyNames.ALL)
        assertTrue("local_first" in RoutingPolicyNames.ALL)
        assertTrue("cloud_first" in RoutingPolicyNames.ALL)
        assertTrue("cloud_only" in RoutingPolicyNames.ALL)
        assertTrue("performance_first" in RoutingPolicyNames.ALL)
    }

    @Test
    fun `quality_first is not a valid policy name`() {
        assertFalse("quality_first" in RoutingPolicyNames.ALL)
    }

    @Test
    fun `RoutingPolicyNames constants match generated RoutingPolicy enum codes`() {
        // Verify that RoutingPolicyNames constants are consistent with
        // the contract-generated RoutingPolicy enum values.
        val generated = ai.octomil.generated.RoutingPolicy.entries
        val generatedCodes = generated.map { it.code }.toSet()

        // Every RoutingPolicyNames constant should exist in the generated enum
        for (name in RoutingPolicyNames.ALL) {
            assertTrue(
                name in generatedCodes,
                "RoutingPolicyNames.$name not found in generated RoutingPolicy enum",
            )
        }
    }

    @Test
    fun `RoutingPolicyNames constant values are correct`() {
        assertEquals("private", RoutingPolicyNames.PRIVATE)
        assertEquals("local_only", RoutingPolicyNames.LOCAL_ONLY)
        assertEquals("local_first", RoutingPolicyNames.LOCAL_FIRST)
        assertEquals("cloud_first", RoutingPolicyNames.CLOUD_FIRST)
        assertEquals("cloud_only", RoutingPolicyNames.CLOUD_ONLY)
        assertEquals("performance_first", RoutingPolicyNames.PERFORMANCE_FIRST)
    }

    // =========================================================================
    // 2. RouteMetadata field presence and mapping
    // =========================================================================

    @Test
    fun `RouteMetadata has all Python SDK fields`() {
        val meta = RouteMetadata(
            locality = "on_device",
            engine = "llama.cpp",
            plannerSource = "server",
            fallbackUsed = false,
            reason = "best match",
        )
        assertEquals("on_device", meta.locality)
        assertEquals("llama.cpp", meta.engine)
        assertEquals("server", meta.plannerSource)
        assertFalse(meta.fallbackUsed)
        assertEquals("best match", meta.reason)
    }

    @Test
    fun `RouteMetadata defaults match Python SDK`() {
        val meta = RouteMetadata()
        assertEquals("", meta.locality)
        assertNull(meta.engine)
        assertEquals("", meta.plannerSource)
        assertFalse(meta.fallbackUsed)
        assertEquals("", meta.reason)
    }

    @Test
    fun `RouteMetadata fromSelection maps local to on_device`() {
        val selection = RuntimeSelection(
            locality = "local",
            engine = "tflite",
            source = "server_plan",
            reason = "server selected",
        )
        val meta = RouteMetadata.fromSelection(selection)
        assertEquals("on_device", meta.locality)
        assertEquals("tflite", meta.engine)
        assertEquals("server", meta.plannerSource)
        assertFalse(meta.fallbackUsed)
    }

    @Test
    fun `RouteMetadata fromSelection maps cloud locality`() {
        val selection = RuntimeSelection(
            locality = "cloud",
            engine = null,
            source = "fallback",
            reason = "no local engine",
        )
        val meta = RouteMetadata.fromSelection(selection)
        assertEquals("cloud", meta.locality)
        assertNull(meta.engine)
        assertEquals("offline", meta.plannerSource)
        assertTrue(meta.fallbackUsed)
    }

    @Test
    fun `RouteMetadata fromSelection maps cache source`() {
        val selection = RuntimeSelection(
            locality = "local",
            engine = "llama.cpp",
            source = "cache",
            reason = "cached benchmark: 25.0 tok/s",
        )
        val meta = RouteMetadata.fromSelection(selection)
        assertEquals("cache", meta.plannerSource)
    }

    @Test
    fun `RouteMetadata fromSelection maps local_default to offline`() {
        val selection = RuntimeSelection(
            locality = "local",
            engine = "tflite",
            source = "local_default",
            reason = "selected explicitly reported local engine: tflite",
        )
        val meta = RouteMetadata.fromSelection(selection)
        assertEquals("offline", meta.plannerSource)
    }

    @Test
    fun `RouteMetadata round-trips through JSON serialization`() {
        val original = RouteMetadata(
            locality = "on_device",
            engine = "llama.cpp",
            plannerSource = "server",
            fallbackUsed = true,
            reason = "fallback: tflite unavailable",
        )
        val encoded = json.encodeToString(RouteMetadata.serializer(), original)
        val decoded = json.decodeFromString(RouteMetadata.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `RouteMetadata JSON uses snake_case field names`() {
        val meta = RouteMetadata(
            locality = "cloud",
            plannerSource = "cache",
            fallbackUsed = true,
            reason = "test",
        )
        val encoded = json.encodeToString(RouteMetadata.serializer(), meta)
        assertTrue(encoded.contains("\"planner_source\""))
        assertTrue(encoded.contains("\"fallback_used\""))
        assertFalse(encoded.contains("\"plannerSource\""))
        assertFalse(encoded.contains("\"fallbackUsed\""))
    }

    // =========================================================================
    // 3. Private policy: no cloud candidates
    // =========================================================================

    @Test
    fun `private policy produces no cloud candidates in resolution`() {
        val device = DeviceRuntimeProfile(
            sdkVersion = "1.0.0",
            arch = "arm64-v8a",
            installedRuntimes = listOf(
                InstalledRuntime.modelCapable(
                    engine = "tflite",
                    model = "gemma-2b",
                    capability = "text",
                ),
            ),
        )
        val planner = RuntimePlanner(
            context = context,
            store = store,
            client = null,
            profileCollector = { device },
        )
        val result = planner.resolve(
            model = "gemma-2b",
            capability = "text",
            routingPolicy = RoutingPolicyNames.PRIVATE,
        )

        assertEquals("local", result.locality)
        assertTrue(
            result.fallbackCandidates.none { it.locality == "cloud" },
            "private policy must never produce cloud fallback candidates",
        )
    }

    @Test
    fun `private policy with no local engine stays local and never goes cloud`() {
        val device = DeviceRuntimeProfile(
            sdkVersion = "1.0.0",
            arch = "arm64-v8a",
            installedRuntimes = emptyList(),
        )
        val planner = RuntimePlanner(
            context = context,
            store = store,
            client = null,
            profileCollector = { device },
        )
        val result = planner.resolve(
            model = "gemma-2b",
            capability = "text",
            routingPolicy = RoutingPolicyNames.PRIVATE,
        )

        assertEquals("local", result.locality, "private policy must stay local even without engines")
        assertNull(result.engine)
        assertEquals("fallback", result.source)
    }

    @Test
    fun `private policy filters cloud candidates from cached plan`() {
        val device = DeviceRuntimeProfile(
            sdkVersion = "1.0.0",
            arch = "arm64-v8a",
            installedRuntimes = emptyList(),
        )
        // Pre-populate cache with a cloud candidate
        val cacheKey = RuntimePlannerStore.makeCacheKey(
            model = "test",
            capability = "text",
            policy = "private",
            sdkVersion = device.sdkVersion,
            platform = device.platform,
            arch = device.arch,
            installedHash = RuntimePlannerStore.installedRuntimesHash(device.installedRuntimes),
        )
        store.putPlan(
            cacheKey,
            RuntimePlanResponse(
                model = "test",
                capability = "text",
                policy = "private",
                candidates = listOf(
                    RuntimeCandidatePlan(locality = "cloud", reason = "cloud candidate"),
                ),
            ),
        )

        val planner = RuntimePlanner(
            context = context,
            store = store,
            client = null,
            profileCollector = { device },
        )
        val result = planner.resolve(
            model = "test",
            capability = "text",
            routingPolicy = RoutingPolicyNames.PRIVATE,
        )

        // Private policy must reject cloud candidates
        assertEquals("local", result.locality, "private policy must not select cloud from cache")
    }

    // =========================================================================
    // 4. Cloud-only policy: no local candidates
    // =========================================================================

    @Test
    fun `cloud_only policy produces no local candidates`() {
        val device = DeviceRuntimeProfile(
            sdkVersion = "1.0.0",
            arch = "arm64-v8a",
            installedRuntimes = listOf(
                InstalledRuntime.modelCapable(
                    engine = "tflite",
                    model = "gemma-2b",
                    capability = "text",
                ),
            ),
        )
        val planner = RuntimePlanner(
            context = context,
            store = store,
            client = null,
            profileCollector = { device },
        )
        val result = planner.resolve(
            model = "gemma-2b",
            capability = "text",
            routingPolicy = RoutingPolicyNames.CLOUD_ONLY,
        )

        assertEquals("cloud", result.locality, "cloud_only must always select cloud")
        assertNull(result.engine, "cloud_only should not select a local engine")
    }

    @Test
    fun `cloud_only ignores local benchmark cache`() {
        val device = DeviceRuntimeProfile(
            sdkVersion = "1.0.0",
            arch = "arm64-v8a",
            installedRuntimes = listOf(
                InstalledRuntime.modelCapable(
                    engine = "tflite",
                    model = "gemma-2b",
                    capability = "text",
                ),
            ),
        )

        // Record a benchmark that proves local capability
        val planner = RuntimePlanner(
            context = context,
            store = store,
            client = null,
            profileCollector = { device },
        )
        planner.recordBenchmark(
            model = "gemma-2b",
            capability = "text",
            engine = "tflite",
            tokensPerSecond = 100.0,
            routingPolicy = RoutingPolicyNames.CLOUD_ONLY,
        )

        val result = planner.resolve(
            model = "gemma-2b",
            capability = "text",
            routingPolicy = RoutingPolicyNames.CLOUD_ONLY,
        )

        assertEquals("cloud", result.locality, "cloud_only must ignore local benchmarks")
    }

    // =========================================================================
    // 5. Benchmark submission rejects banned metadata keys
    // =========================================================================

    @Test
    fun `BenchmarkSubmission rejects prompt key`() {
        assertBannedKeyRejected("prompt")
    }

    @Test
    fun `BenchmarkSubmission rejects input key`() {
        assertBannedKeyRejected("input")
    }

    @Test
    fun `BenchmarkSubmission rejects output key`() {
        assertBannedKeyRejected("output")
    }

    @Test
    fun `BenchmarkSubmission rejects response key`() {
        assertBannedKeyRejected("response")
    }

    @Test
    fun `BenchmarkSubmission rejects file key`() {
        assertBannedKeyRejected("file")
    }

    @Test
    fun `BenchmarkSubmission rejects path key`() {
        assertBannedKeyRejected("path")
    }

    @Test
    fun `BenchmarkSubmission rejects file_path key`() {
        assertBannedKeyRejected("file_path")
    }

    @Test
    fun `BenchmarkSubmission rejects user key`() {
        assertBannedKeyRejected("user")
    }

    @Test
    fun `BenchmarkSubmission rejects user_input key`() {
        assertBannedKeyRejected("user_input")
    }

    @Test
    fun `BenchmarkSubmission rejects user_data key`() {
        assertBannedKeyRejected("user_data")
    }

    @Test
    fun `BenchmarkSubmission rejects content key`() {
        assertBannedKeyRejected("content")
    }

    @Test
    fun `BenchmarkSubmission rejects message key`() {
        assertBannedKeyRejected("message")
    }

    @Test
    fun `BenchmarkSubmission rejects messages key`() {
        assertBannedKeyRejected("messages")
    }

    @Test
    fun `BenchmarkSubmission rejects text key`() {
        assertBannedKeyRejected("text")
    }

    @Test
    fun `BenchmarkSubmission rejects query key`() {
        assertBannedKeyRejected("query")
    }

    @Test
    fun `BenchmarkSubmission rejects context key`() {
        assertBannedKeyRejected("context")
    }

    @Test
    fun `BenchmarkSubmission rejects instruction key`() {
        assertBannedKeyRejected("instruction")
    }

    @Test
    fun `BenchmarkSubmission rejects system_prompt key`() {
        assertBannedKeyRejected("system_prompt")
    }

    @Test
    fun `BenchmarkSubmission rejects banned keys case-insensitively`() {
        assertBannedKeyRejected("PROMPT")
        assertBannedKeyRejected("Prompt")
        assertBannedKeyRejected("USER_INPUT")
        assertBannedKeyRejected("File_Path")
    }

    @Test
    fun `BenchmarkSubmission accepts safe metadata keys`() {
        val payload = BenchmarkSubmission.create(
            model = "gemma-2b",
            capability = "text",
            engine = "tflite",
            device = DeviceRuntimeProfile(sdkVersion = "1.0", arch = "arm64-v8a"),
            success = true,
            tokensPerSecond = 15.0,
            metadata = mapOf(
                "selection_source" to "benchmark",
                "batch_size" to "1",
                "warmup_runs" to "3",
            ),
        )
        assertEquals("gemma-2b", payload.model)
        assertEquals(15.0, payload.tokensPerSecond)
        assertEquals(3, payload.metadata.size)
    }

    @Test
    fun `BenchmarkSubmission canonicalizes engine names`() {
        val payload = BenchmarkSubmission.create(
            model = "gemma-2b",
            capability = "text",
            engine = "llamacpp",  // alias
            device = DeviceRuntimeProfile(sdkVersion = "1.0", arch = "arm64-v8a"),
            success = true,
        )
        assertEquals("llama.cpp", payload.engine)
    }

    @Test
    fun `BANNED_METADATA_KEYS contains all expected keys`() {
        val expected = setOf(
            "prompt", "input", "output", "response", "file", "path",
            "file_path", "user", "user_input", "user_data", "content",
            "message", "messages", "text", "query", "context",
            "instruction", "system_prompt",
        )
        assertEquals(expected, BenchmarkSubmission.BANNED_METADATA_KEYS)
    }

    // =========================================================================
    // 6. RuntimeCandidatePlan field parity
    // =========================================================================

    @Test
    fun `RuntimeCandidatePlan has all Python SDK fields`() {
        val candidate = RuntimeCandidatePlan(
            locality = "local",
            priority = 1,
            confidence = 0.95,
            reason = "best match",
            engine = "llama.cpp",
            engineVersionConstraint = ">=0.5",
            artifact = RuntimeArtifactPlan(modelId = "gemma-2b"),
            benchmarkRequired = true,
        )
        assertEquals("local", candidate.locality)
        assertEquals(1, candidate.priority)
        assertEquals(0.95, candidate.confidence)
        assertEquals("best match", candidate.reason)
        assertEquals("llama.cpp", candidate.engine)
        assertEquals(">=0.5", candidate.engineVersionConstraint)
        assertNotNull(candidate.artifact)
        assertTrue(candidate.benchmarkRequired)
    }

    @Test
    fun `RuntimeCandidatePlan JSON uses snake_case for benchmark_required`() {
        val candidate = RuntimeCandidatePlan(
            locality = "local",
            benchmarkRequired = true,
        )
        val encoded = json.encodeToString(RuntimeCandidatePlan.serializer(), candidate)
        assertTrue(encoded.contains("\"benchmark_required\":true"))
        assertFalse(encoded.contains("\"benchmarkRequired\""))
    }

    // =========================================================================
    // 7. RuntimePlanResponse field parity
    // =========================================================================

    @Test
    fun `RuntimePlanResponse has all Python SDK fields`() {
        val plan = RuntimePlanResponse(
            model = "gemma-2b",
            capability = "text",
            policy = "local_first",
            candidates = listOf(
                RuntimeCandidatePlan(locality = "local"),
            ),
            fallbackCandidates = listOf(
                RuntimeCandidatePlan(locality = "cloud"),
            ),
            planTtlSeconds = 86400,
            serverGeneratedAt = "2026-04-12T00:00:00Z",
        )
        assertEquals("gemma-2b", plan.model)
        assertEquals("text", plan.capability)
        assertEquals("local_first", plan.policy)
        assertEquals(1, plan.candidates.size)
        assertEquals(1, plan.fallbackCandidates.size)
        assertEquals(86400, plan.planTtlSeconds)
        assertEquals("2026-04-12T00:00:00Z", plan.serverGeneratedAt)
    }

    @Test
    fun `RuntimePlanResponse default TTL is 7 days`() {
        val plan = RuntimePlanResponse(
            model = "test",
            capability = "text",
            policy = "auto",
            candidates = emptyList(),
        )
        assertEquals(604800, plan.planTtlSeconds)
    }

    @Test
    fun `RuntimePlanResponse JSON uses snake_case`() {
        val plan = RuntimePlanResponse(
            model = "test",
            capability = "text",
            policy = "auto",
            candidates = emptyList(),
            planTtlSeconds = 3600,
            serverGeneratedAt = "2026-04-12T00:00:00Z",
        )
        val encoded = json.encodeToString(RuntimePlanResponse.serializer(), plan)
        assertTrue(encoded.contains("\"plan_ttl_seconds\""))
        assertTrue(encoded.contains("\"server_generated_at\""))
        assertTrue(encoded.contains("\"fallback_candidates\""))
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun assertBannedKeyRejected(key: String) {
        val exception = assertFailsWith<IllegalArgumentException> {
            BenchmarkSubmission.create(
                model = "gemma-2b",
                capability = "text",
                engine = "tflite",
                device = DeviceRuntimeProfile(sdkVersion = "1.0", arch = "arm64-v8a"),
                success = true,
                metadata = mapOf(key to "some value"),
            )
        }
        assertTrue(
            exception.message?.contains("banned") == true,
            "Exception message should mention banned keys, got: ${exception.message}",
        )
    }
}
