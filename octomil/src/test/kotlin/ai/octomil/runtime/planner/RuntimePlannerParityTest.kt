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
 * and benchmark submission rules match the contract-backed shape defined in
 * octomil-contracts. These tests are the Android counterpart to the Python
 * SDK's planner parity assertions.
 *
 * Groups:
 * 1. Policy name parity with Python SDK
 * 2. RouteMetadata nested contract shape and field presence
 * 3. Private policy: no cloud candidates
 * 4. Cloud-only policy: no local candidates
 * 5. Benchmark submission rejects banned metadata keys
 * 6. RuntimeCandidatePlan field parity
 * 7. RuntimePlanResponse field parity
 * 8. Locality contract: "on_device" never appears
 * 9. Execution mode correctness
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
    // 2. RouteMetadata nested contract shape and field presence
    // =========================================================================

    @Test
    fun `RouteMetadata has nested contract shape with all fields`() {
        val meta = RouteMetadata(
            status = "selected",
            execution = RouteExecution(
                locality = "local",
                mode = "sdk_runtime",
                engine = "llama.cpp",
            ),
            model = RouteModel(
                requested = RouteModelRequested(
                    ref = "gemma-2b",
                    kind = "model",
                    capability = "text",
                ),
                resolved = RouteModelResolved(
                    id = "gemma-2b-id",
                    slug = "gemma-2b",
                ),
            ),
            artifact = RouteArtifact(
                id = "art-1",
                format = "gguf",
            ),
            planner = PlannerInfo(source = "server"),
            fallback = FallbackInfo(used = false),
            reason = RouteReason(code = "ok", message = "best match"),
        )
        assertEquals("selected", meta.status)
        assertEquals("local", meta.execution?.locality)
        assertEquals("sdk_runtime", meta.execution?.mode)
        assertEquals("llama.cpp", meta.execution?.engine)
        assertEquals("gemma-2b", meta.model.requested.ref)
        assertEquals("model", meta.model.requested.kind)
        assertEquals("text", meta.model.requested.capability)
        assertEquals("gemma-2b-id", meta.model.resolved?.id)
        assertEquals("art-1", meta.artifact?.id)
        assertEquals("gguf", meta.artifact?.format)
        assertEquals("server", meta.planner.source)
        assertFalse(meta.fallback.used)
        assertEquals("ok", meta.reason.code)
        assertEquals("best match", meta.reason.message)
    }

    @Test
    fun `RouteMetadata defaults match contract spec`() {
        val meta = RouteMetadata(
            model = RouteModel(
                requested = RouteModelRequested(ref = "test"),
            ),
        )
        assertEquals("selected", meta.status)
        assertNull(meta.execution)
        assertEquals("test", meta.model.requested.ref)
        assertEquals("unknown", meta.model.requested.kind)
        assertNull(meta.model.requested.capability)
        assertNull(meta.model.resolved)
        assertNull(meta.artifact)
        assertEquals("offline", meta.planner.source)
        assertFalse(meta.fallback.used)
        assertEquals("", meta.reason.code)
        assertEquals("", meta.reason.message)
    }

    @Test
    fun `RouteMetadata fromSelection maps local to local with sdk_runtime mode`() {
        val selection = RuntimeSelection(
            locality = "local",
            engine = "tflite",
            source = "server_plan",
            reason = "server selected",
        )
        val meta = RouteMetadata.fromSelection(selection, modelRef = "gemma-2b", capability = "text")
        assertEquals("local", meta.execution?.locality)
        assertEquals("sdk_runtime", meta.execution?.mode)
        assertEquals("tflite", meta.execution?.engine)
        assertEquals("server", meta.planner.source)
        assertFalse(meta.fallback.used)
        assertEquals("gemma-2b", meta.model.requested.ref)
        assertEquals("text", meta.model.requested.capability)
    }

    @Test
    fun `RouteMetadata fromSelection maps cloud locality with hosted_gateway mode`() {
        val selection = RuntimeSelection(
            locality = "cloud",
            engine = null,
            source = "fallback",
            reason = "no local engine",
        )
        val meta = RouteMetadata.fromSelection(selection)
        assertEquals("cloud", meta.execution?.locality)
        assertEquals("hosted_gateway", meta.execution?.mode)
        assertNull(meta.execution?.engine)
        assertEquals("offline", meta.planner.source)
        assertTrue(meta.fallback.used)
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
        assertEquals("cache", meta.planner.source)
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
        assertEquals("offline", meta.planner.source)
    }

    @Test
    fun `RouteMetadata fromSelection populates artifact from selection artifact`() {
        val selection = RuntimeSelection(
            locality = "local",
            engine = "tflite",
            source = "server_plan",
            reason = "server selected",
            artifact = RuntimeArtifactPlan(
                modelId = "gemma-2b",
                artifactId = "art-123",
                modelVersion = "1.0",
                format = "tflite",
                digest = "sha256:abc",
            ),
        )
        val meta = RouteMetadata.fromSelection(selection, modelRef = "gemma-2b")
        val art = meta.artifact
        assertNotNull(art)
        assertEquals("art-123", art.id)
        assertEquals("1.0", art.version)
        assertEquals("tflite", art.format)
        assertEquals("sha256:abc", art.digest)
    }

    @Test
    fun `RouteMetadata round-trips through JSON serialization`() {
        val original = RouteMetadata(
            status = "selected",
            execution = RouteExecution(
                locality = "local",
                mode = "sdk_runtime",
                engine = "llama.cpp",
            ),
            model = RouteModel(
                requested = RouteModelRequested(ref = "gemma-2b", kind = "model", capability = "text"),
            ),
            planner = PlannerInfo(source = "server"),
            fallback = FallbackInfo(used = true),
            reason = RouteReason(code = "fallback", message = "fallback: tflite unavailable"),
        )
        val encoded = json.encodeToString(RouteMetadata.serializer(), original)
        val decoded = json.decodeFromString(RouteMetadata.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `RouteMetadata JSON uses snake_case for nested field names`() {
        val meta = RouteMetadata(
            execution = RouteExecution(locality = "cloud", mode = "hosted_gateway"),
            model = RouteModel(
                requested = RouteModelRequested(ref = "test"),
                resolved = RouteModelResolved(versionId = "v1", variantId = "var1"),
            ),
            artifact = RouteArtifact(
                cache = ArtifactCache(status = "hit", managedBy = "octomil"),
            ),
            planner = PlannerInfo(source = "cache"),
            fallback = FallbackInfo(used = true),
            reason = RouteReason(code = "ok", message = "test"),
        )
        val encoded = json.encodeToString(RouteMetadata.serializer(), meta)
        // Nested snake_case fields
        assertTrue(encoded.contains("\"version_id\""), "Should use snake_case version_id, got: $encoded")
        assertTrue(encoded.contains("\"variant_id\""), "Should use snake_case variant_id, got: $encoded")
        assertTrue(encoded.contains("\"managed_by\""), "Should use snake_case managed_by, got: $encoded")
        // Should NOT contain camelCase
        assertFalse(encoded.contains("\"versionId\""), "Should not contain camelCase versionId")
        assertFalse(encoded.contains("\"variantId\""), "Should not contain camelCase variantId")
        assertFalse(encoded.contains("\"managedBy\""), "Should not contain camelCase managedBy")
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
    // 8. Locality contract: "on_device" never appears
    // =========================================================================

    @Test
    fun `RouteMetadata fromSelection never produces on_device as locality`() {
        // Local selection -- should be "local", NOT "on_device"
        val localSelection = RuntimeSelection(
            locality = "local",
            engine = "tflite",
            source = "server_plan",
            reason = "server selected",
        )
        val localMeta = RouteMetadata.fromSelection(localSelection)
        assertEquals("local", localMeta.execution?.locality, "Locality must be 'local', never 'on_device'")
        assertFalse(
            localMeta.execution?.locality == "on_device",
            "on_device must never appear in RouteMetadata.execution.locality",
        )

        // Cloud selection
        val cloudSelection = RuntimeSelection(
            locality = "cloud",
            engine = null,
            source = "fallback",
            reason = "no local engine",
        )
        val cloudMeta = RouteMetadata.fromSelection(cloudSelection)
        assertEquals("cloud", cloudMeta.execution?.locality)
    }

    @Test
    fun `on_device never appears in any RouteMetadata locality field across policies`() {
        val sources = listOf("server_plan", "cache", "local_default", "fallback")
        val localities = listOf("local", "cloud")

        for (source in sources) {
            for (locality in localities) {
                val selection = RuntimeSelection(
                    locality = locality,
                    engine = if (locality == "local") "tflite" else null,
                    source = source,
                    reason = "test $source $locality",
                )
                val meta = RouteMetadata.fromSelection(selection)
                assertFalse(
                    meta.execution?.locality == "on_device",
                    "on_device must never appear; source=$source locality=$locality produced: ${meta.execution?.locality}",
                )
            }
        }
    }

    // =========================================================================
    // 9. Execution mode correctness
    // =========================================================================

    @Test
    fun `execution mode is sdk_runtime for local selection`() {
        val selection = RuntimeSelection(
            locality = "local",
            engine = "llama.cpp",
            source = "server_plan",
            reason = "selected",
        )
        val meta = RouteMetadata.fromSelection(selection)
        assertEquals("sdk_runtime", meta.execution?.mode, "Local selection must use sdk_runtime mode")
    }

    @Test
    fun `execution mode is hosted_gateway for cloud selection`() {
        val selection = RuntimeSelection(
            locality = "cloud",
            engine = null,
            source = "fallback",
            reason = "no local engine",
        )
        val meta = RouteMetadata.fromSelection(selection)
        assertEquals("hosted_gateway", meta.execution?.mode, "Cloud selection must use hosted_gateway mode")
    }

    @Test
    fun `nested structure access works correctly`() {
        val selection = RuntimeSelection(
            locality = "local",
            engine = "tflite",
            source = "cache",
            reason = "cached benchmark",
        )
        val route = RouteMetadata.fromSelection(selection, modelRef = "gemma-2b", capability = "text")

        // Test nested structure access patterns
        assertEquals("local", route.execution?.locality)
        assertEquals("sdk_runtime", route.execution?.mode)
        assertEquals("tflite", route.execution?.engine)
        assertEquals("cache", route.planner.source)
        assertFalse(route.fallback.used)
        assertEquals("gemma-2b", route.model.requested.ref)
        assertEquals("text", route.model.requested.capability)
        assertEquals("ok", route.reason.code)
        assertEquals("cached benchmark", route.reason.message)
    }

    @Test
    fun `fallback selection sets fallback used and reason code`() {
        val selection = RuntimeSelection(
            locality = "cloud",
            engine = null,
            source = "fallback",
            reason = "no local engine available -- falling back to cloud",
        )
        val route = RouteMetadata.fromSelection(selection)
        assertTrue(route.fallback.used, "Fallback source should set fallback.used = true")
        assertEquals("fallback", route.reason.code, "Fallback source should set reason.code = fallback")
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
