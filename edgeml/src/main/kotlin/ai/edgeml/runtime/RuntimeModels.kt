package ai.edgeml.runtime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request to the server for an adaptation recommendation based on current device state.
 *
 * Sent when the device's battery, thermal, or compute conditions change significantly
 * enough that the current inference delegate may no longer be optimal.
 */
@Serializable
data class AdaptationRequest(
    @SerialName("device_id")
    val deviceId: String,
    @SerialName("model_id")
    val modelId: String,
    @SerialName("battery_level")
    val batteryLevel: Int,
    @SerialName("thermal_state")
    val thermalState: String,
    @SerialName("current_format")
    val currentFormat: String,
    @SerialName("current_executor")
    val currentExecutor: String,
)

/**
 * Server response recommending which executor and compute units to use
 * given the device's current state.
 */
@Serializable
data class AdaptationRecommendation(
    @SerialName("recommended_executor")
    val recommendedExecutor: String,
    @SerialName("recommended_compute_units")
    val recommendedComputeUnits: String,
    @SerialName("throttle_inference")
    val throttleInference: Boolean,
    @SerialName("reduce_batch_size")
    val reduceBatchSize: Boolean,
)

/**
 * Request to the server for a fallback recommendation after a delegate or format fails.
 *
 * Sent when the device cannot load or run a model with its current configuration
 * and needs guidance on an alternative.
 */
@Serializable
data class FallbackRequest(
    @SerialName("device_id")
    val deviceId: String,
    @SerialName("model_id")
    val modelId: String,
    @SerialName("version")
    val version: String,
    @SerialName("failed_format")
    val failedFormat: String,
    @SerialName("failed_executor")
    val failedExecutor: String,
    @SerialName("error_message")
    val errorMessage: String,
)

/**
 * Server response with a fallback model format, executor, and download URL.
 */
@Serializable
data class FallbackRecommendation(
    @SerialName("fallback_format")
    val fallbackFormat: String,
    @SerialName("fallback_executor")
    val fallbackExecutor: String,
    @SerialName("download_url")
    val downloadURL: String,
    @SerialName("runtime_config")
    val runtimeConfig: Map<String, String>? = null,
)
