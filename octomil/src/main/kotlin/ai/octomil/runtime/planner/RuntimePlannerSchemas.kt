package ai.octomil.runtime.planner

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Runtime planner request/response schemas -- mirrors the server contract
 * defined in `POST /api/v2/runtime/plan`.
 *
 * These data classes are shared across all SDK platforms (Python, Node, iOS,
 * Android, Browser). Field names use snake_case to match the JSON wire format;
 * Kotlin accessors remain idiomatic via `@SerialName`.
 */

// =========================================================================
// Request types
// =========================================================================

/**
 * A locally-installed inference engine detected on this device.
 */
@Serializable
data class InstalledRuntime(
    @SerialName("engine") val engine: String,
    @SerialName("version") val version: String? = null,
    @SerialName("available") val available: Boolean = true,
    @SerialName("accelerator") val accelerator: String? = null,
    @SerialName("metadata") val metadata: Map<String, String> = emptyMap(),
)

/**
 * Canonical runtime engine identifiers shared with the server planner.
 */
internal object RuntimeEngineIds {
    private val aliases = mapOf(
        "mlx" to "mlx-lm",
        "mlx_lm" to "mlx-lm",
        "mlxlm" to "mlx-lm",
        "llamacpp" to "llama.cpp",
        "llama_cpp" to "llama.cpp",
        "llama-cpp" to "llama.cpp",
        "whisper" to "whisper.cpp",
        "whispercpp" to "whisper.cpp",
        "whisper_cpp" to "whisper.cpp",
        "whisper-cpp" to "whisper.cpp",
    )

    fun canonical(engine: String): String {
        val normalized = engine.trim().lowercase()
        return aliases[normalized] ?: normalized
    }

    fun canonicalOrNull(engine: String?): String? = engine?.let(::canonical)
}

internal fun InstalledRuntime.canonicalized(): InstalledRuntime =
    copy(engine = RuntimeEngineIds.canonical(engine))

/**
 * Hardware and software profile sent to the server planner endpoint.
 *
 * Collects only privacy-safe hardware descriptors. No user data, prompts,
 * responses, file paths, or PII is included.
 */
@Serializable
data class DeviceRuntimeProfile(
    @SerialName("sdk") val sdk: String = "android",
    @SerialName("sdk_version") val sdkVersion: String,
    @SerialName("platform") val platform: String = "Android",
    @SerialName("arch") val arch: String,
    @SerialName("os_version") val osVersion: String? = null,
    @SerialName("api_level") val apiLevel: Int? = null,
    @SerialName("chip") val chip: String? = null,
    @SerialName("device_model") val deviceModel: String? = null,
    @SerialName("ram_total_bytes") val ramTotalBytes: Long? = null,
    @SerialName("gpu_core_count") val gpuCoreCount: Int? = null,
    @SerialName("accelerators") val accelerators: List<String> = emptyList(),
    @SerialName("installed_runtimes") val installedRuntimes: List<InstalledRuntime> = emptyList(),
)

/**
 * Request body sent to `POST /api/v2/runtime/plan`.
 */
@Serializable
data class RuntimePlanRequest(
    @SerialName("model") val model: String,
    @SerialName("capability") val capability: String,
    @SerialName("device") val device: DeviceRuntimeProfile,
    @SerialName("routing_policy") val routingPolicy: String? = null,
    @SerialName("allow_cloud_fallback") val allowCloudFallback: Boolean? = null,
)

// =========================================================================
// Response types
// =========================================================================

/**
 * Artifact recommendation from the server planner.
 */
@Serializable
data class RuntimeArtifactPlan(
    @SerialName("model_id") val modelId: String,
    @SerialName("artifact_id") val artifactId: String? = null,
    @SerialName("model_version") val modelVersion: String? = null,
    @SerialName("format") val format: String? = null,
    @SerialName("quantization") val quantization: String? = null,
    @SerialName("uri") val uri: String? = null,
    @SerialName("digest") val digest: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    @SerialName("min_ram_bytes") val minRamBytes: Long? = null,
)

/**
 * A single candidate in a runtime plan (local or cloud).
 */
@Serializable
data class RuntimeCandidatePlan(
    @SerialName("locality") val locality: String,
    @SerialName("priority") val priority: Int = 0,
    @SerialName("confidence") val confidence: Double = 0.0,
    @SerialName("reason") val reason: String = "",
    @SerialName("engine") val engine: String? = null,
    @SerialName("engine_version_constraint") val engineVersionConstraint: String? = null,
    @SerialName("artifact") val artifact: RuntimeArtifactPlan? = null,
    @SerialName("benchmark_required") val benchmarkRequired: Boolean = false,
)

/**
 * Full plan response from `POST /api/v2/runtime/plan`.
 */
@Serializable
data class RuntimePlanResponse(
    @SerialName("model") val model: String,
    @SerialName("capability") val capability: String,
    @SerialName("policy") val policy: String,
    @SerialName("candidates") val candidates: List<RuntimeCandidatePlan>,
    @SerialName("fallback_candidates") val fallbackCandidates: List<RuntimeCandidatePlan> = emptyList(),
    @SerialName("plan_ttl_seconds") val planTtlSeconds: Int = 604800,
    @SerialName("server_generated_at") val serverGeneratedAt: String = "",
)

// =========================================================================
// Local selection result
// =========================================================================

/**
 * Final resolved runtime selection from the planner.
 *
 * @property locality Where inference will run: "local" or "cloud".
 * @property engine Engine wire name (e.g. "llama.cpp", "tflite") or null.
 * @property artifact Artifact recommendation if available.
 * @property benchmarkRan Whether a local benchmark was run during resolution.
 * @property source How the selection was made: "cache", "server_plan", "local_default", "fallback".
 * @property fallbackCandidates Remaining fallback candidates from the plan.
 * @property reason Human-readable explanation.
 */
@Serializable
data class RuntimeSelection(
    @SerialName("locality") val locality: String,
    @SerialName("engine") val engine: String? = null,
    @SerialName("artifact") val artifact: RuntimeArtifactPlan? = null,
    @SerialName("benchmark_ran") val benchmarkRan: Boolean = false,
    @SerialName("source") val source: String = "",
    @SerialName("fallback_candidates") val fallbackCandidates: List<RuntimeCandidatePlan> = emptyList(),
    @SerialName("reason") val reason: String = "",
)

// =========================================================================
// Benchmark telemetry (upload payload)
// =========================================================================

/**
 * Privacy-safe benchmark telemetry payload.
 *
 * No prompts, responses, file paths, or user input. Only hardware performance
 * metrics and device identifiers are included.
 */
@Serializable
data class BenchmarkTelemetryPayload(
    @SerialName("source") val source: String = "planner",
    @SerialName("model") val model: String,
    @SerialName("capability") val capability: String,
    @SerialName("engine") val engine: String,
    @SerialName("device") val device: DeviceRuntimeProfile,
    @SerialName("success") val success: Boolean,
    @SerialName("tokens_per_second") val tokensPerSecond: Double? = null,
    @SerialName("ttft_ms") val ttftMs: Double? = null,
    @SerialName("peak_memory_bytes") val peakMemoryBytes: Long? = null,
    @SerialName("metadata") val metadata: Map<String, String> = emptyMap(),
)
