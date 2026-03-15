package ai.octomil.chat

/**
 * Performance metrics captured during token generation.
 *
 * Matches `GenerationMetrics` definition in `octomil.chat_message` contract schema.
 */
data class GenerationMetrics(
    /** Time to first token in milliseconds. */
    val ttftMs: Long,
    /** Decode throughput in tokens per second. */
    val decodeTokensPerSec: Double,
    /** Total tokens generated. */
    val totalTokens: Int,
    /** Total generation latency in milliseconds. */
    val totalLatencyMs: Long,
)
