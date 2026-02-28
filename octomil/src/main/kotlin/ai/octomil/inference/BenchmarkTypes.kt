package ai.octomil.inference

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Consistent benchmark result type shared across all Octomil SDKs.
 */
@Serializable
data class BenchmarkResult(
    @SerialName("engine_name")
    val engineName: String,
    @SerialName("tokens_per_second")
    val tokensPerSecond: Double,
    @SerialName("ttft_ms")
    val ttftMs: Double,
    @SerialName("memory_mb")
    val memoryMb: Double,
    @SerialName("error")
    val error: String? = null,
    @SerialName("metadata")
    val metadata: Map<String, String>? = null,
) {
    /** True when the benchmark completed without error. */
    val ok: Boolean get() = error == null
}

/**
 * Result of detecting whether an engine is available on this device.
 */
@Serializable
data class DetectionResult(
    @SerialName("engine")
    val engine: String,
    @SerialName("available")
    val available: Boolean,
    @SerialName("info")
    val info: String,
)

/**
 * An engine paired with its benchmark result, used for ranking.
 */
@Serializable
data class RankedEngine(
    @SerialName("engine")
    val engine: String,
    @SerialName("result")
    val result: BenchmarkResult,
)

/**
 * Cross-SDK inference metrics for a completed generation.
 */
@Serializable
data class InferenceMetrics(
    @SerialName("ttfc_ms")
    val ttfcMs: Double,
    @SerialName("prompt_tokens")
    val promptTokens: Int,
    @SerialName("total_tokens")
    val totalTokens: Int,
    @SerialName("tokens_per_second")
    val tokensPerSecond: Double,
    @SerialName("total_duration_ms")
    val totalDurationMs: Double,
    @SerialName("cache_hit")
    val cacheHit: Boolean,
    @SerialName("attention_backend")
    val attentionBackend: String,
)

/**
 * A single chunk of generated output with performance metadata.
 */
@Serializable
data class GenerationChunk(
    @SerialName("text")
    val text: String,
    @SerialName("token_count")
    val tokenCount: Int,
    @SerialName("tokens_per_second")
    val tokensPerSecond: Double,
    @SerialName("finish_reason")
    val finishReason: String? = null,
)

/**
 * Statistics for the inference cache.
 */
@Serializable
data class CacheStats(
    @SerialName("hits")
    val hits: Int,
    @SerialName("misses")
    val misses: Int,
    @SerialName("hit_rate")
    val hitRate: Double,
    @SerialName("entries")
    val entries: Int,
    @SerialName("memory_mb")
    val memoryMb: Double,
)
