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
) {
    companion object
}

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

@Serializable
data class CandidateGate(
    @SerialName("code") val code: String,
    @SerialName("required") val required: Boolean = true,
    @SerialName("threshold_number") val thresholdNumber: Double? = null,
    @SerialName("threshold_string") val thresholdString: String? = null,
    @SerialName("window_seconds") val windowSeconds: Int? = null,
    @SerialName("source") val source: String = "server",
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
    @SerialName("gates") val gates: List<CandidateGate> = emptyList(),
)

/**
 * Server-resolved application context for @app/{slug}/{capability} references.
 */
@Serializable
data class AppResolution(
    @SerialName("app_id") val appId: String,
    @SerialName("app_slug") val appSlug: String? = null,
    @SerialName("capability") val capability: String,
    @SerialName("routing_policy") val routingPolicy: String,
    @SerialName("selected_model") val selectedModel: String,
    @SerialName("selected_model_variant_id") val selectedModelVariantId: String? = null,
    @SerialName("selected_model_version") val selectedModelVersion: String? = null,
    @SerialName("artifact_candidates") val artifactCandidates: List<RuntimeArtifactPlan> = emptyList(),
    @SerialName("preferred_engines") val preferredEngines: List<String> = emptyList(),
    @SerialName("fallback_policy") val fallbackPolicy: String? = null,
    @SerialName("plan_ttl_seconds") val planTtlSeconds: Int = 604800,
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
    @SerialName("fallback_allowed") val fallbackAllowed: Boolean = true,
    @SerialName("server_generated_at") val serverGeneratedAt: String = "",
    @SerialName("app_resolution") val appResolution: AppResolution? = null,
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
// Routing policy names — cross-SDK canonical constants
// =========================================================================

/**
 * Canonical routing policy name constants shared across SDKs.
 *
 * These match the Python SDK's `RoutingPolicy` enum values and the
 * contract-generated `ai.octomil.generated.RoutingPolicy` enum codes.
 * Use these constants when constructing [RuntimePlanRequest] or
 * interpreting server plan responses.
 *
 * Policy semantics:
 * - [PRIVATE]: never send inference inputs to cloud; on-device only, no telemetry.
 * - [LOCAL_ONLY]: always use on-device inference; fail if none available.
 * - [LOCAL_FIRST]: prefer on-device; fall back to cloud when unavailable.
 * - [CLOUD_FIRST]: prefer cloud; fall back to on-device when unavailable.
 * - [CLOUD_ONLY]: always use cloud; never attempt local execution.
 * - [PERFORMANCE_FIRST]: choose lowest-latency viable route.
 *
 * Note: `quality_first` is intentionally excluded — it is not a valid policy.
 */
object RoutingPolicyNames {
    const val PRIVATE = "private"
    const val LOCAL_ONLY = "local_only"
    const val LOCAL_FIRST = "local_first"
    const val CLOUD_FIRST = "cloud_first"
    const val CLOUD_ONLY = "cloud_only"
    const val PERFORMANCE_FIRST = "performance_first"

    /** All valid policy names as a set, for validation. */
    val ALL: Set<String> = setOf(
        PRIVATE, LOCAL_ONLY, LOCAL_FIRST,
        CLOUD_FIRST, CLOUD_ONLY, PERFORMANCE_FIRST,
    )
}

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

// =========================================================================
// Benchmark submission — privacy-safe wrapper with banned-key validation
// =========================================================================

/**
 * Privacy-safe benchmark submission with metadata validation.
 *
 * Wraps [BenchmarkTelemetryPayload] construction with explicit rejection of
 * metadata keys that could leak user data (prompts, inputs, file paths, etc.).
 *
 * Use [BenchmarkSubmission.create] instead of constructing
 * [BenchmarkTelemetryPayload] directly when accepting caller-provided metadata.
 */
object BenchmarkSubmission {

    /**
     * Metadata keys that MUST NOT appear in benchmark submissions.
     *
     * These keys could carry user data (prompts, model outputs, file paths,
     * PII). The server rejects payloads containing them; the SDK validates
     * client-side to fail fast.
     */
    val BANNED_METADATA_KEYS: Set<String> = setOf(
        "prompt",
        "input",
        "output",
        "response",
        "file",
        "path",
        "file_path",
        "user",
        "user_input",
        "user_data",
        "content",
        "message",
        "messages",
        "text",
        "query",
        "context",
        "instruction",
        "system_prompt",
    )

    /**
     * Create a [BenchmarkTelemetryPayload] after validating metadata keys.
     *
     * @throws IllegalArgumentException if any [BANNED_METADATA_KEYS] are present.
     */
    fun create(
        model: String,
        capability: String,
        engine: String,
        device: DeviceRuntimeProfile,
        success: Boolean,
        tokensPerSecond: Double? = null,
        ttftMs: Double? = null,
        peakMemoryBytes: Long? = null,
        metadata: Map<String, String> = emptyMap(),
    ): BenchmarkTelemetryPayload {
        val violations = metadata.keys.filter { it.lowercase() in BANNED_METADATA_KEYS }
        require(violations.isEmpty()) {
            "Benchmark metadata contains banned keys that could leak user data: $violations"
        }

        return BenchmarkTelemetryPayload(
            source = "planner",
            model = model,
            capability = capability,
            engine = RuntimeEngineIds.canonical(engine),
            device = device,
            success = success,
            tokensPerSecond = tokensPerSecond,
            ttftMs = ttftMs,
            peakMemoryBytes = peakMemoryBytes,
            metadata = metadata,
        )
    }
}
