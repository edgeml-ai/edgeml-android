package ai.octomil.runtime.planner

import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuntimePlannerSchemasTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    // =========================================================================
    // InstalledRuntime
    // =========================================================================

    @Test
    fun `InstalledRuntime defaults are correct`() {
        val rt = InstalledRuntime(engine = "tflite")
        assertEquals("tflite", rt.engine)
        assertNull(rt.version)
        assertTrue(rt.available)
        assertNull(rt.accelerator)
        assertTrue(rt.metadata.isEmpty())
    }

    @Test
    fun `InstalledRuntime serializes to JSON`() {
        val rt = InstalledRuntime(
            engine = "llama_cpp",
            version = "1.0",
            available = true,
            accelerator = "cpu",
        )
        val encoded = json.encodeToString(InstalledRuntime.serializer(), rt)
        assertTrue(encoded.contains("\"engine\":\"llama_cpp\""))
        assertTrue(encoded.contains("\"version\":\"1.0\""))
        assertTrue(encoded.contains("\"accelerator\":\"cpu\""))
    }

    @Test
    fun `InstalledRuntime deserializes from JSON`() {
        val jsonStr = """{"engine":"onnxruntime","version":"2.0","available":false}"""
        val rt = json.decodeFromString(InstalledRuntime.serializer(), jsonStr)
        assertEquals("onnxruntime", rt.engine)
        assertEquals("2.0", rt.version)
        assertEquals(false, rt.available)
    }

    @Test
    fun `InstalledRuntime ignores unknown keys`() {
        val jsonStr = """{"engine":"tflite","unknown_field":"value","available":true}"""
        val rt = json.decodeFromString(InstalledRuntime.serializer(), jsonStr)
        assertEquals("tflite", rt.engine)
    }

    // =========================================================================
    // DeviceRuntimeProfile
    // =========================================================================

    @Test
    fun `DeviceRuntimeProfile has correct defaults`() {
        val profile = DeviceRuntimeProfile(
            sdkVersion = "1.0.0",
            arch = "arm64-v8a",
        )
        assertEquals("android", profile.sdk)
        assertEquals("Android", profile.platform)
        assertNull(profile.osVersion)
        assertNull(profile.apiLevel)
        assertNull(profile.chip)
        assertNull(profile.deviceModel)
        assertNull(profile.ramTotalBytes)
        assertNull(profile.gpuCoreCount)
        assertTrue(profile.accelerators.isEmpty())
        assertTrue(profile.installedRuntimes.isEmpty())
    }

    @Test
    fun `DeviceRuntimeProfile round-trips through JSON`() {
        val profile = DeviceRuntimeProfile(
            sdkVersion = "2.0.0",
            arch = "arm64-v8a",
            osVersion = "14",
            apiLevel = 34,
            chip = "Snapdragon 8 Gen 3",
            deviceModel = "Samsung Galaxy S24",
            ramTotalBytes = 8_589_934_592L,
            accelerators = listOf("nnapi", "gpu"),
            installedRuntimes = listOf(
                InstalledRuntime(engine = "tflite"),
                InstalledRuntime(engine = "llama_cpp", version = "0.5"),
            ),
        )
        val encoded = json.encodeToString(DeviceRuntimeProfile.serializer(), profile)
        val decoded = json.decodeFromString(DeviceRuntimeProfile.serializer(), encoded)

        assertEquals(profile.sdkVersion, decoded.sdkVersion)
        assertEquals(profile.arch, decoded.arch)
        assertEquals(profile.apiLevel, decoded.apiLevel)
        assertEquals(profile.chip, decoded.chip)
        assertEquals(profile.ramTotalBytes, decoded.ramTotalBytes)
        assertEquals(2, decoded.installedRuntimes.size)
        assertEquals("llama_cpp", decoded.installedRuntimes[1].engine)
    }

    // =========================================================================
    // RuntimePlanRequest
    // =========================================================================

    @Test
    fun `RuntimePlanRequest serializes with all fields`() {
        val req = RuntimePlanRequest(
            model = "gemma-2b",
            capability = "text",
            device = DeviceRuntimeProfile(sdkVersion = "1.0", arch = "arm64-v8a"),
            routingPolicy = "local_first",
            allowCloudFallback = true,
        )
        val encoded = json.encodeToString(RuntimePlanRequest.serializer(), req)
        assertTrue(encoded.contains("\"model\":\"gemma-2b\""))
        assertTrue(encoded.contains("\"capability\":\"text\""))
        assertTrue(encoded.contains("\"routing_policy\":\"local_first\""))
        assertTrue(encoded.contains("\"allow_cloud_fallback\":true"))
    }

    // =========================================================================
    // RuntimeArtifactPlan
    // =========================================================================

    @Test
    fun `RuntimeArtifactPlan deserializes from server response`() {
        val jsonStr = """{
            "model_id": "gemma-2b",
            "artifact_id": "art_123",
            "model_version": "2.0.0",
            "format": "gguf",
            "quantization": "q4_0",
            "uri": "https://cdn.octomil.com/gemma-2b.gguf",
            "digest": "sha256:abc123",
            "size_bytes": 1073741824,
            "min_ram_bytes": 4294967296
        }"""
        val artifact = json.decodeFromString(RuntimeArtifactPlan.serializer(), jsonStr)
        assertEquals("gemma-2b", artifact.modelId)
        assertEquals("art_123", artifact.artifactId)
        assertEquals("gguf", artifact.format)
        assertEquals("q4_0", artifact.quantization)
        assertEquals(1_073_741_824L, artifact.sizeBytes)
        assertEquals(4_294_967_296L, artifact.minRamBytes)
    }

    // =========================================================================
    // RuntimeCandidatePlan
    // =========================================================================

    @Test
    fun `RuntimeCandidatePlan defaults are correct`() {
        val candidate = RuntimeCandidatePlan(locality = "local")
        assertEquals(0, candidate.priority)
        assertEquals(0.0, candidate.confidence)
        assertEquals("", candidate.reason)
        assertNull(candidate.engine)
        assertNull(candidate.artifact)
        assertEquals(false, candidate.benchmarkRequired)
    }

    @Test
    fun `RuntimeCandidatePlan with artifact round-trips`() {
        val candidate = RuntimeCandidatePlan(
            locality = "local",
            priority = 1,
            confidence = 0.95,
            reason = "best match for device",
            engine = "llama_cpp",
            artifact = RuntimeArtifactPlan(
                modelId = "llama-8b",
                format = "gguf",
                quantization = "q4_k_m",
            ),
            benchmarkRequired = true,
        )
        val encoded = json.encodeToString(RuntimeCandidatePlan.serializer(), candidate)
        val decoded = json.decodeFromString(RuntimeCandidatePlan.serializer(), encoded)

        assertEquals("local", decoded.locality)
        assertEquals(1, decoded.priority)
        assertEquals(0.95, decoded.confidence)
        assertEquals("llama_cpp", decoded.engine)
        val decodedArtifact = decoded.artifact
        assertNotNull(decodedArtifact)
        assertEquals("gguf", decodedArtifact.format)
        assertTrue(decoded.benchmarkRequired)
    }

    // =========================================================================
    // RuntimePlanResponse
    // =========================================================================

    @Test
    fun `RuntimePlanResponse deserializes from server JSON`() {
        val jsonStr = """{
            "model": "gemma-2b",
            "capability": "text",
            "policy": "local_first",
            "candidates": [
                {
                    "locality": "local",
                    "priority": 1,
                    "confidence": 0.9,
                    "reason": "strong match",
                    "engine": "llama_cpp",
                    "benchmark_required": false
                },
                {
                    "locality": "cloud",
                    "priority": 2,
                    "confidence": 1.0,
                    "reason": "always available"
                }
            ],
            "fallback_candidates": [
                {
                    "locality": "local",
                    "priority": 3,
                    "confidence": 0.5,
                    "reason": "slower but available",
                    "engine": "tflite"
                }
            ],
            "plan_ttl_seconds": 86400,
            "server_generated_at": "2026-04-12T00:00:00Z"
        }"""
        val plan = json.decodeFromString(RuntimePlanResponse.serializer(), jsonStr)
        assertEquals("gemma-2b", plan.model)
        assertEquals("text", plan.capability)
        assertEquals("local_first", plan.policy)
        assertEquals(2, plan.candidates.size)
        assertEquals(1, plan.fallbackCandidates.size)
        assertEquals(86400, plan.planTtlSeconds)
        assertEquals("llama_cpp", plan.candidates[0].engine)
        assertEquals("cloud", plan.candidates[1].locality)
        assertEquals("tflite", plan.fallbackCandidates[0].engine)
    }

    @Test
    fun `RuntimePlanResponse defaults are correct`() {
        val plan = RuntimePlanResponse(
            model = "test",
            capability = "text",
            policy = "auto",
            candidates = emptyList(),
        )
        assertTrue(plan.fallbackCandidates.isEmpty())
        assertEquals(604800, plan.planTtlSeconds)
        assertEquals("", plan.serverGeneratedAt)
    }

    // =========================================================================
    // RuntimeSelection
    // =========================================================================

    @Test
    fun `RuntimeSelection defaults are correct`() {
        val sel = RuntimeSelection(locality = "local")
        assertNull(sel.engine)
        assertNull(sel.artifact)
        assertEquals(false, sel.benchmarkRan)
        assertEquals("", sel.source)
        assertTrue(sel.fallbackCandidates.isEmpty())
        assertEquals("", sel.reason)
    }

    // =========================================================================
    // BenchmarkTelemetryPayload
    // =========================================================================

    @Test
    fun `BenchmarkTelemetryPayload serializes without user data`() {
        val payload = BenchmarkTelemetryPayload(
            model = "gemma-2b",
            capability = "text",
            engine = "llama_cpp",
            device = DeviceRuntimeProfile(sdkVersion = "1.0", arch = "arm64-v8a"),
            success = true,
            tokensPerSecond = 12.5,
            ttftMs = 250.0,
            peakMemoryBytes = 1_073_741_824L,
        )
        val encoded = json.encodeToString(BenchmarkTelemetryPayload.serializer(), payload)

        // Verify expected fields are present
        assertTrue(encoded.contains("\"model\":\"gemma-2b\""))
        assertTrue(encoded.contains("\"tokens_per_second\":12.5"))

        // Verify no user data fields exist
        assertTrue(!encoded.contains("prompt"))
        assertTrue(!encoded.contains("response"))
        assertTrue(!encoded.contains("file_path"))
    }

    // =========================================================================
    // CachedBenchmark
    // =========================================================================

    @Test
    fun `CachedBenchmark round-trips through JSON`() {
        val bm = CachedBenchmark(
            model = "gemma-2b",
            capability = "text",
            engine = "llama_cpp",
            tokensPerSecond = 15.3,
            ttftMs = 200.0,
            memoryMb = 1024.0,
        )
        val encoded = json.encodeToString(CachedBenchmark.serializer(), bm)
        val decoded = json.decodeFromString(CachedBenchmark.serializer(), encoded)
        assertEquals(bm, decoded)
    }
}
