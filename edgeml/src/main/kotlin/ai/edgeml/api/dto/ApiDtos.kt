package ai.edgeml.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request to register a device with the EdgeML server.
 * Field names aligned with server API contract.
 */
@Serializable
data class DeviceRegistrationRequest(
    @SerialName("device_identifier")
    val deviceIdentifier: String,
    @SerialName("org_id")
    val orgId: String,
    @SerialName("platform")
    val platform: String = "android",
    @SerialName("os_version")
    val osVersion: String,
    @SerialName("sdk_version")
    val sdkVersion: String,
    @SerialName("manufacturer")
    val manufacturer: String? = null,
    @SerialName("model")
    val model: String? = null,
    @SerialName("locale")
    val locale: String? = null,
    @SerialName("region")
    val region: String? = null,
    @SerialName("app_version")
    val appVersion: String? = null,
    @SerialName("capabilities")
    val capabilities: DeviceCapabilities? = null,
)

/**
 * Device hardware capabilities for registration.
 */
@Serializable
data class DeviceCapabilities(
    @SerialName("cpu_architecture")
    val cpuArchitecture: String? = null,
    @SerialName("gpu_available")
    val gpuAvailable: Boolean = false,
    @SerialName("nnapi_available")
    val nnapiAvailable: Boolean = false,
    @SerialName("total_memory_mb")
    val totalMemoryMb: Long? = null,
    @SerialName("available_storage_mb")
    val availableStorageMb: Long? = null,
)

/**
 * Response from device registration.
 * Server returns the device record with server-assigned UUID.
 */
@Serializable
data class DeviceRegistrationResponse(
    @SerialName("id")
    val id: String,
    @SerialName("device_identifier")
    val deviceIdentifier: String,
    @SerialName("org_id")
    val orgId: String,
    @SerialName("platform")
    val platform: String,
    @SerialName("status")
    val status: String,
    @SerialName("manufacturer")
    val manufacturer: String? = null,
    @SerialName("model")
    val model: String? = null,
    @SerialName("os_version")
    val osVersion: String? = null,
    @SerialName("sdk_version")
    val sdkVersion: String? = null,
    @SerialName("locale")
    val locale: String? = null,
    @SerialName("region")
    val region: String? = null,
    @SerialName("last_heartbeat")
    val lastHeartbeat: String? = null,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
    @SerialName("api_token")
    val apiToken: String? = null,
)

// =========================================================================
// Heartbeat
// =========================================================================

/**
 * Request to send device heartbeat.
 */
@Serializable
data class HeartbeatRequest(
    @SerialName("sdk_version")
    val sdkVersion: String? = null,
    @SerialName("os_version")
    val osVersion: String? = null,
    @SerialName("app_version")
    val appVersion: String? = null,
    @SerialName("battery_level")
    val batteryLevel: Int? = null,
    @SerialName("is_charging")
    val isCharging: Boolean? = null,
    @SerialName("available_storage_mb")
    val availableStorageMb: Long? = null,
    @SerialName("available_memory_mb")
    val availableMemoryMb: Long? = null,
    @SerialName("network_type")
    val networkType: String? = null,
)

/**
 * Response from heartbeat request.
 */
@Serializable
data class HeartbeatResponse(
    @SerialName("acknowledged")
    val acknowledged: Boolean,
    @SerialName("server_time")
    val serverTime: String? = null,
)

// =========================================================================
// Device Groups
// =========================================================================

/**
 * Device group information.
 */
@Serializable
data class DeviceGroup(
    @SerialName("id")
    val id: String,
    @SerialName("name")
    val name: String,
    @SerialName("description")
    val description: String? = null,
    @SerialName("group_type")
    val groupType: String,
    @SerialName("is_active")
    val isActive: Boolean,
    @SerialName("device_count")
    val deviceCount: Int,
    @SerialName("tags")
    val tags: List<String>? = null,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
)

/**
 * Response containing device groups.
 */
@Serializable
data class DeviceGroupsResponse(
    @SerialName("groups")
    val groups: List<DeviceGroup>,
    @SerialName("count")
    val count: Int,
)

/**
 * Group membership information.
 */
@Serializable
data class GroupMembership(
    @SerialName("id")
    val id: String,
    @SerialName("device_id")
    val deviceId: String,
    @SerialName("group_id")
    val groupId: String,
    @SerialName("group_name")
    val groupName: String? = null,
    @SerialName("membership_type")
    val membershipType: String,
    @SerialName("created_at")
    val createdAt: String,
)

/**
 * Response containing device group memberships.
 */
@Serializable
data class GroupMembershipsResponse(
    @SerialName("memberships")
    val memberships: List<GroupMembership>,
    @SerialName("count")
    val count: Int,
)

/**
 * Request to assign a device to a model version.
 */
@Serializable
data class AssignmentRequest(
    @SerialName("version")
    val version: String? = null,
    @SerialName("experiment_id")
    val experimentId: String? = null,
    @SerialName("variant")
    val variant: String = "default",
    @SerialName("assignment_reason")
    val assignmentReason: String = "sdk_registration",
)

/**
 * Response for version resolution.
 */
@Serializable
data class VersionResolutionResponse(
    @SerialName("version")
    val version: String,
    @SerialName("source")
    val source: String,
    @SerialName("experiment_id")
    val experimentId: String? = null,
    @SerialName("rollout_id")
    val rolloutId: Long? = null,
    @SerialName("device_bucket")
    val deviceBucket: Int? = null,
)

/**
 * Model metadata response.
 */
@Serializable
data class ModelResponse(
    @SerialName("id")
    val id: String,
    @SerialName("org_id")
    val orgId: String,
    @SerialName("name")
    val name: String,
    @SerialName("framework")
    val framework: String,
    @SerialName("use_case")
    val useCase: String,
    @SerialName("description")
    val description: String? = null,
    @SerialName("version_count")
    val versionCount: Int,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
)

/**
 * Model version metadata response.
 */
@Serializable
data class ModelVersionResponse(
    @SerialName("id")
    val id: String,
    @SerialName("model_id")
    val modelId: String,
    @SerialName("version")
    val version: String,
    @SerialName("status")
    val status: String,
    @SerialName("storage_path")
    val storagePath: String,
    @SerialName("format")
    val format: String,
    @SerialName("checksum")
    val checksum: String,
    @SerialName("size_bytes")
    val sizeBytes: Long,
    @SerialName("metrics")
    val metrics: Map<String, Double>? = null,
    @SerialName("description")
    val description: String? = null,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
)

/**
 * Model download URL response.
 */
// TODO(optimization): Add optimization metadata to this response.
//   The server should return model properties so the client can select
//   the right delegate and runtime configuration:
//     @SerialName("quantization") val quantization: String? = null,
//       // e.g., "float32", "float16", "int8_dynamic", "int8_full"
//     @SerialName("recommended_delegates") val recommendedDelegates: List<String>? = null,
//       // e.g., ["gpu"], ["xnnpack"], ["nnapi", "gpu"]
//     @SerialName("input_shape") val inputShape: List<Int>? = null,
//     @SerialName("output_shape") val outputShape: List<Int>? = null,
//     @SerialName("has_training_signature") val hasTrainingSignature: Boolean? = null,
//   This enables the client to configure TFLiteTrainer before loading the model.
//
// TODO(server): Generate INT8 and float16 TFLite variants on model upload.
//   When a model version is published, the server conversion pipeline should:
//   1. Take the uploaded SavedModel/ONNX/TFLite float32 source
//   2. Run TFLiteConverter with:
//      - Default: float32 (no quantization)
//      - converter.optimizations = [tf.lite.Optimize.DEFAULT] → dynamic range
//      - converter.target_spec.supported_types = [tf.float16] → float16
//      - Full int8 with representative_dataset → int8
//   3. Store all variants with their optimization metadata
//   4. Return the right variant based on the client's format query param
@Serializable
data class ModelDownloadResponse(
    @SerialName("download_url")
    val downloadUrl: String,
    @SerialName("expires_at")
    val expiresAt: String,
    @SerialName("checksum")
    val checksum: String,
    @SerialName("size_bytes")
    val sizeBytes: Long,
)

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
 * Error response from the server.
 */
@Serializable
data class ErrorResponse(
    @SerialName("detail")
    val detail: String,
    @SerialName("status_code")
    val statusCode: Int? = null,
)

/**
 * Health check response.
 */
@Serializable
data class HealthResponse(
    @SerialName("status")
    val status: String,
    @SerialName("version")
    val version: String? = null,
    @SerialName("timestamp")
    val timestamp: String? = null,
)

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

/**
 * Device policy configuration from organization settings.
 * Controls device training behavior based on server-side policy.
 */
@Serializable
data class DevicePolicyResponse(
    @SerialName("battery_threshold")
    val batteryThreshold: Int,
    @SerialName("network_policy")
    val networkPolicy: String,
    @SerialName("sampling_policy")
    val samplingPolicy: String? = null,
    @SerialName("training_window")
    val trainingWindow: String? = null,
)

// =========================================================================
// Round Management
// =========================================================================

/**
 * A federated learning round returned from the server.
 */
@Serializable
data class RoundAssignment(
    @SerialName("id")
    val id: String,
    @SerialName("org_id")
    val orgId: String,
    @SerialName("model_id")
    val modelId: String,
    @SerialName("version_id")
    val versionId: String,
    @SerialName("state")
    val state: String,
    @SerialName("min_clients")
    val minClients: Int,
    @SerialName("max_clients")
    val maxClients: Int,
    @SerialName("client_selection_strategy")
    val clientSelectionStrategy: String,
    @SerialName("aggregation_type")
    val aggregationType: String,
    @SerialName("timeout_minutes")
    val timeoutMinutes: Int,
    @SerialName("differential_privacy")
    val differentialPrivacy: Boolean = false,
    @SerialName("dp_epsilon")
    val dpEpsilon: Double? = null,
    @SerialName("dp_delta")
    val dpDelta: Double? = null,
    @SerialName("secure_aggregation")
    val secureAggregation: Boolean = false,
    @SerialName("secagg_threshold")
    val secaggThreshold: Int? = null,
    @SerialName("selected_client_count")
    val selectedClientCount: Int = 0,
    @SerialName("received_update_count")
    val receivedUpdateCount: Int = 0,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("client_selection_started_at")
    val clientSelectionStartedAt: String? = null,
    @SerialName("aggregation_completed_at")
    val aggregationCompletedAt: String? = null,
)

/**
 * Response wrapping a list of rounds.
 * The server may return a list directly or wrapped in a `rounds` field.
 */
@Serializable
data class RoundsListResponse(
    @SerialName("rounds")
    val rounds: List<RoundAssignment> = emptyList(),
)

// =========================================================================
// Secure Aggregation
// =========================================================================

/**
 * Request to join a SecAgg session for a round.
 */
@Serializable
data class SecAggKeyExchangeRequest(
    @SerialName("device_id")
    val deviceId: String,
)

/**
 * Response from joining a SecAgg session.
 */
@Serializable
data class SecAggSessionResponse(
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("round_id")
    val roundId: String,
    @SerialName("threshold")
    val threshold: Int,
    @SerialName("total_clients")
    val totalClients: Int,
    @SerialName("participant_ids")
    val participantIds: List<String>,
    @SerialName("state")
    val state: String,
)

/**
 * Request to submit SecAgg shares to the server.
 */
@Serializable
data class SecAggShareSubmitRequest(
    @SerialName("device_id")
    val deviceId: String,
    @SerialName("shares")
    val shares: Map<String, String>,
    @SerialName("verification_tag")
    val verificationTag: String,
)

/**
 * Response from submitting SecAgg shares.
 */
@Serializable
data class SecAggShareSubmitResponse(
    @SerialName("accepted")
    val accepted: Boolean,
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("message")
    val message: String? = null,
)
