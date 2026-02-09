package ai.edgeml.inference

import kotlinx.coroutines.flow.Flow

/**
 * Output modality for generative inference.
 */
enum class Modality {
    TEXT,
    IMAGE,
    AUDIO,
    VIDEO;

    /** Wire-format value for the server API. */
    val value: String get() = name.lowercase()
}

/**
 * A single chunk emitted during streaming inference.
 *
 * @property index Zero-based chunk index within the session.
 * @property data  Modality-specific payload (tokens, pixel rows, audio frames, etc.).
 * @property modality The modality this chunk belongs to.
 * @property timestamp Epoch millis when the chunk was produced.
 * @property latencyMs Milliseconds since the previous chunk (or session start for index 0).
 */
data class InferenceChunk(
    val index: Int,
    val data: ByteArray,
    val modality: Modality,
    val timestamp: Long,
    val latencyMs: Double,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as InferenceChunk
        return index == other.index &&
            data.contentEquals(other.data) &&
            modality == other.modality &&
            timestamp == other.timestamp
    }

    override fun hashCode(): Int {
        var result = index
        result = 31 * result + data.contentHashCode()
        result = 31 * result + modality.hashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}

/**
 * Aggregated metrics for a completed streaming inference session.
 */
data class StreamingInferenceResult(
    /** Client-generated UUID grouping all chunks from one generation. */
    val sessionId: String,
    /** Output modality. */
    val modality: Modality,
    /** Time to first chunk in milliseconds. */
    val ttfcMs: Double,
    /** Average inter-chunk latency in milliseconds. */
    val avgChunkLatencyMs: Double,
    /** Total number of chunks produced. */
    val totalChunks: Int,
    /** Wall-clock duration of the generation in milliseconds. */
    val totalDurationMs: Double,
    /** Throughput in chunks per second. */
    val throughput: Double,
)

/**
 * Interface that modality-specific inference engines implement.
 *
 * Engines emit raw [InferenceChunk] values via a [Flow]. Timing
 * instrumentation is added by the SDK wrapper in [EdgeMLClient].
 */
fun interface StreamingInferenceEngine {
    /**
     * Generate output for the given input, emitting chunks via a cold [Flow].
     *
     * @param input Modality-specific input (prompt, conditioning image, etc.).
     * @param modality Output modality.
     */
    fun generate(input: Any, modality: Modality): Flow<InferenceChunk>
}
