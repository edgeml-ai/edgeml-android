package ai.octomil.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// =========================================================================
// Inference Events
// =========================================================================

/**
 * Metrics payload for a streaming inference event.
 */
@Serializable
data class InferenceEventMetrics(
    @SerialName("ttfc_ms")
    val ttfcMs: Double? = null,
    @SerialName("chunk_index")
    val chunkIndex: Int? = null,
    @SerialName("chunk_latency_ms")
    val chunkLatencyMs: Double? = null,
    @SerialName("total_chunks")
    val totalChunks: Int? = null,
    @SerialName("total_duration_ms")
    val totalDurationMs: Double? = null,
    @SerialName("throughput")
    val throughput: Double? = null,
)

/**
 * Request body for POST /api/v1/inference/events.
 */
@Serializable
data class InferenceEventRequest(
    @SerialName("device_id")
    val deviceId: String,
    @SerialName("model_id")
    val modelId: String,
    @SerialName("version")
    val version: String,
    @SerialName("modality")
    val modality: String,
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("event_type")
    val eventType: String,
    @SerialName("timestamp_ms")
    val timestampMs: Long,
    @SerialName("metrics")
    val metrics: InferenceEventMetrics? = null,
    @SerialName("org_id")
    val orgId: String? = null,
)

/**
 * Response from inference events endpoint.
 */
@Serializable
data class InferenceEventResponse(
    @SerialName("status")
    val status: String,
    @SerialName("session_id")
    val sessionId: String,
)
