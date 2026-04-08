package ai.octomil.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Training event to report to the server.
 */
@Serializable
data class TrainingEventRequest(
    @SerialName("device_id")
    val deviceId: String,
    @SerialName("model_id")
    val modelId: String,
    @SerialName("version")
    val version: String,
    @SerialName("event_type")
    val eventType: String,
    @SerialName("timestamp")
    val timestamp: String,
    @SerialName("metrics")
    val metrics: Map<String, Double>? = null,
    @SerialName("metadata")
    val metadata: Map<String, String>? = null,
)

/**
 * Gradient update to submit to the server.
 */
@Serializable
data class GradientUpdateRequest(
    @SerialName("device_id")
    val deviceId: String,
    @SerialName("model_id")
    val modelId: String,
    @SerialName("version")
    val version: String,
    @SerialName("round_id")
    val roundId: String,
    @SerialName("gradients_path")
    val gradientsPath: String? = null,
    @SerialName("num_samples")
    val numSamples: Int,
    @SerialName("training_time_ms")
    val trainingTimeMs: Long,
    @SerialName("metrics")
    val metrics: TrainingMetrics,
)

/**
 * Training metrics from local training.
 */
@Serializable
data class TrainingMetrics(
    @SerialName("loss")
    val loss: Double,
    @SerialName("accuracy")
    val accuracy: Double? = null,
    @SerialName("num_batches")
    val numBatches: Int,
    @SerialName("learning_rate")
    val learningRate: Double? = null,
    @SerialName("custom_metrics")
    val customMetrics: Map<String, Double>? = null,
)

/**
 * Response for gradient submission.
 */
@Serializable
data class GradientUpdateResponse(
    @SerialName("accepted")
    val accepted: Boolean,
    @SerialName("round_id")
    val roundId: String,
    @SerialName("message")
    val message: String? = null,
)

/**
 * Request to upload trained weights to the server.
 */
@Serializable
data class WeightUploadRequest(
    @SerialName("model_id")
    val modelId: String,
    @SerialName("version")
    val version: String,
    @SerialName("device_id")
    val deviceId: String? = null,
    @SerialName("weights_data")
    val weightsData: String,
    @SerialName("sample_count")
    val sampleCount: Int,
    @SerialName("metrics")
    val metrics: Map<String, Double>? = null,
    @SerialName("dp_epsilon_used")
    val dpEpsilonUsed: Double? = null,
    @SerialName("dp_noise_scale")
    val dpNoiseScale: Double? = null,
    @SerialName("dp_mechanism")
    val dpMechanism: String? = null,
    @SerialName("dp_clipping_norm")
    val dpClippingNorm: Double? = null,
)
