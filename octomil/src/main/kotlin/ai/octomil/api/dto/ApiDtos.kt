package ai.octomil.api.dto

import ai.octomil.models.ServerModelContract
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request to register a device with the Octomil server.
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
    @SerialName("timezone")
    val timezone: String? = null,
    @SerialName("metadata")
    val metadata: Map<String, String>? = null,
    @SerialName("device_info")
    val deviceInfo: DeviceInfoRequest? = null,
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
 * Device hardware info for registration (nested under device_info).
 */
@Serializable
data class DeviceInfoRequest(
    @SerialName("manufacturer")
    val manufacturer: String? = null,
    @SerialName("model")
    val model: String? = null,
    @SerialName("cpu_architecture")
    val cpuArchitecture: String? = null,
    @SerialName("gpu_available")
    val gpuAvailable: Boolean? = null,
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
    @SerialName("app_version")
    val appVersion: String? = null,
    @SerialName("timezone")
    val timezone: String? = null,
    @SerialName("capabilities")
    val capabilities: Map<String, String>? = null,
    @SerialName("heartbeat_interval_seconds")
    val heartbeatIntervalSeconds: Int? = null,
    @SerialName("access_token")
    val accessToken: String? = null,
    @SerialName("expires_at")
    val expiresAt: String? = null,
    @SerialName("refresh_token")
    val refreshToken: String? = null,
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
    @SerialName("id")
    val id: String? = null,
    @SerialName("device_identifier")
    val deviceIdentifier: String? = null,
    @SerialName("status")
    val status: String? = null,
    @SerialName("last_heartbeat")
    val lastHeartbeat: String? = null,
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
 * Request for POST /api/v1/models/{model_id}/versions/{version}/resolve.
 */
@Serializable
data class ModelResolveRequest(
    @SerialName("platform")
    val platform: String = "android",
    @SerialName("model")
    val model: String? = null,
    @SerialName("manufacturer")
    val manufacturer: String? = null,
    @SerialName("cpu_architecture")
    val cpuArchitecture: String? = null,
    @SerialName("os_version")
    val osVersion: String? = null,
    @SerialName("total_memory_mb")
    val totalMemoryMb: Long? = null,
    @SerialName("gpu_available")
    val gpuAvailable: Boolean = false,
    @SerialName("npu_available")
    val npuAvailable: Boolean = false,
    @SerialName("supported_runtimes")
    val supportedRuntimes: List<String> = emptyList(),
)

/**
 * Response for POST /api/v1/models/{model_id}/versions/{version}/resolve.
 */
@Serializable
data class ModelResolveResponse(
    @SerialName("model_id")
    val modelId: String,
    @SerialName("version")
    val version: String,
    @SerialName("format")
    val format: String,
    @SerialName("quantization")
    val quantization: String? = null,
    @SerialName("executor")
    val executor: String? = null,
    @SerialName("download_url")
    val downloadUrl: String? = null,
    @SerialName("available_formats")
    val availableFormats: List<String> = emptyList(),
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
    /** Server-extracted model contract (input/output tensor specs). */
    @SerialName("model_contract")
    val modelContract: ServerModelContract? = null,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
)

/**
 * Model download URL response.
 *
 * Includes optional optimization metadata so the client can select the right
 * delegate and runtime configuration before loading the model. All metadata
 * fields are nullable for backwards compatibility — older servers that don't
 * populate them will just return nulls.
 */
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
    /** Quantization type: "float32", "float16", "int8_dynamic", "int8_full" */
    @SerialName("quantization")
    val quantization: String? = null,
    /** Recommended TFLite delegates for this model variant, e.g. ["gpu"], ["xnnpack"] */
    @SerialName("recommended_delegates")
    val recommendedDelegates: List<String>? = null,
    /** Model input tensor shape, e.g. [1, 224, 224, 3] */
    @SerialName("input_shape")
    val inputShape: List<Int>? = null,
    /** Model output tensor shape, e.g. [1, 1000] */
    @SerialName("output_shape")
    val outputShape: List<Int>? = null,
    /** Whether the model includes a TFLite "train" signature for on-device training */
    @SerialName("has_training_signature")
    val hasTrainingSignature: Boolean? = null,
    /** Server-extracted model contract (input/output tensor specs). */
    @SerialName("model_contract")
    val modelContract: ServerModelContract? = null,
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
 *
 * The [code] field carries the canonical error code string (e.g. "model_not_found")
 * from the server's structured error envelope. Use
 * [ai.octomil.errors.OctomilErrorCode.fromContractCode] to map it to the SDK enum.
 */
@Serializable
data class ErrorResponse(
    @SerialName("detail")
    val detail: String,
    @SerialName("status_code")
    val statusCode: Int? = null,
    @SerialName("code")
    val code: String? = null,
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
    @SerialName("client_index")
    val clientIndex: Int? = null,
    @SerialName("privacy_budget")
    val privacyBudget: Double? = null,
    @SerialName("key_length")
    val keyLength: Int? = null,
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

// =========================================================================
// Model Updates
// =========================================================================

/**
 * Information about an available model update.
 */
@Serializable
data class ModelUpdateInfo(
    @SerialName("new_version")
    val newVersion: String,
    @SerialName("current_version")
    val currentVersion: String,
    @SerialName("is_required")
    val isRequired: Boolean,
    @SerialName("release_notes")
    val releaseNotes: String? = null,
    @SerialName("update_size")
    val updateSize: Long,
)

// =========================================================================
// Weight Upload
// =========================================================================

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

// =========================================================================
// Secure Aggregation Phase 2-3
// =========================================================================

/**
 * Request to submit masked model update during SecAgg Phase 2.
 */
@Serializable
data class SecAggMaskedInputRequest(
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("device_id")
    val deviceId: String,
    @SerialName("masked_weights_data")
    val maskedWeightsData: String,
    @SerialName("sample_count")
    val sampleCount: Int,
    @SerialName("metrics")
    val metrics: Map<String, Double>? = null,
)

/**
 * Server response when requesting unmasking info during SecAgg Phase 3.
 */
@Serializable
data class SecAggUnmaskResponse(
    @SerialName("dropped_client_indices")
    val droppedClientIndices: List<Int>,
    @SerialName("unmasking_required")
    val unmaskingRequired: Boolean,
)

/**
 * Request to submit unmasking shares during SecAgg Phase 3.
 */
@Serializable
data class SecAggUnmaskRequest(
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("device_id")
    val deviceId: String,
    @SerialName("unmask_data")
    val unmaskData: String,
)

// =========================================================================
// Desired State / Observed State
// =========================================================================

/**
 * Server response for GET /api/v1/devices/{device_id}/desired-state.
 * Describes the target state the device should converge toward.
 *
 * Hard cutover: uses `models` array of [DesiredModelEntry], not `artifacts`.
 */
@Serializable
data class DesiredStateResponse(
    @SerialName("schema_version")
    val schemaVersion: String = "1.4.0",
    @SerialName("device_id")
    val deviceId: String,
    @SerialName("generated_at")
    val generatedAt: String,
    @SerialName("active_binding")
    val activeBinding: String? = null,
    @SerialName("models")
    val models: List<DesiredModelEntry> = emptyList(),
    @SerialName("policy_config")
    val policyConfig: Map<String, String>? = null,
    @SerialName("federation_offers")
    val federationOffers: List<FederationOffer> = emptyList(),
    @SerialName("gc_eligible_artifact_ids")
    val gcEligibleArtifactIds: List<String> = emptyList(),
)

/**
 * Per-model entry in server-authoritative desired state.
 * Specifies the target version, activation policy, and artifact manifest.
 */
@Serializable
data class DesiredModelEntry(
    @SerialName("model_id")
    val modelId: String,
    @SerialName("desired_version")
    val desiredVersion: String,
    @SerialName("current_channel")
    val currentChannel: String? = null,
    @SerialName("delivery_mode")
    val deliveryMode: String? = null,
    @SerialName("activation_policy")
    val activationPolicy: String? = null,
    @SerialName("engine_policy")
    val enginePolicy: EnginePolicyDto? = null,
    @SerialName("artifact_manifest")
    val artifactManifest: ArtifactManifestDto? = null,
    @SerialName("rollout_id")
    val rolloutId: String? = null,
)

/**
 * Engine constraints for runtime executor selection.
 */
@Serializable
data class EnginePolicyDto(
    @SerialName("allowed")
    val allowed: List<String>? = null,
    @SerialName("forced")
    val forced: String? = null,
)

/**
 * Download manifest for a model artifact.
 */
@Serializable
data class ArtifactManifestDto(
    @SerialName("artifact_id")
    val artifactId: String,
    @SerialName("model_id")
    val modelId: String,
    @SerialName("version")
    val version: String,
    @SerialName("format")
    val format: String,
    @SerialName("total_bytes")
    val totalBytes: Long,
    @SerialName("sha256")
    val sha256: String? = null,
    @SerialName("cdn_base_url")
    val cdnBaseUrl: String? = null,
    @SerialName("url_expires_at")
    val urlExpiresAt: String? = null,
    @SerialName("chunks")
    val chunks: List<ArtifactChunkDto> = emptyList(),
    @SerialName("entrypoint")
    val entrypoint: String? = null,
    @SerialName("engine_compatibility")
    val engineCompatibility: List<String>? = null,
    @SerialName("is_adapter")
    val isAdapter: Boolean = false,
    @SerialName("base_model_artifact_id")
    val baseModelArtifactId: String? = null,
)

/**
 * A single chunk within an artifact download manifest.
 */
@Serializable
data class ArtifactChunkDto(
    @SerialName("index")
    val index: Int,
    @SerialName("offset")
    val offset: Long,
    @SerialName("size")
    val size: Long,
    @SerialName("sha256")
    val sha256: String,
)

/**
 * A federated learning round the server is offering to this device.
 */
@Serializable
data class FederationOffer(
    @SerialName("round_id")
    val roundId: String,
    @SerialName("job_id")
    val jobId: String? = null,
    @SerialName("expires_at")
    val expiresAt: String? = null,
)

/**
 * Request body for POST /api/v1/devices/{device_id}/observed-state.
 *
 * Hard cutover: uses `models` array of [ObservedModelStatus], not `artifactStatuses`.
 */
@Serializable
data class ObservedStateRequest(
    @SerialName("schema_version")
    val schemaVersion: String = "1.4.0",
    @SerialName("device_id")
    val deviceId: String,
    @SerialName("reported_at")
    val reportedAt: String,
    @SerialName("models")
    val models: List<ObservedModelStatus> = emptyList(),
    @SerialName("sdk_version")
    val sdkVersion: String? = null,
    @SerialName("os_version")
    val osVersion: String? = null,
)

/**
 * Per-model observed status reported to the server.
 */
@Serializable
data class ObservedModelStatus(
    @SerialName("model_id")
    val modelId: String,
    @SerialName("status")
    val status: String,
    @SerialName("installed_version")
    val installedVersion: String? = null,
    @SerialName("active_version")
    val activeVersion: String? = null,
    @SerialName("health")
    val health: String? = null,
    @SerialName("last_error")
    val lastError: String? = null,
)

@Serializable
data class ModelInventoryEntry(
    @SerialName("modelId")
    val modelId: String,
    @SerialName("version")
    val version: String,
    @SerialName("artifactId")
    val artifactId: String? = null,
    @SerialName("status")
    val status: String,
)

@Serializable
data class ActiveVersionEntry(
    @SerialName("modelId")
    val modelId: String,
    @SerialName("version")
    val version: String,
)

@Serializable
data class DeviceSyncRequest(
    @SerialName("schemaVersion")
    val schemaVersion: String = "1.12.0",
    @SerialName("deviceId")
    val deviceId: String,
    @SerialName("requestedAt")
    val requestedAt: String,
    @SerialName("knownStateVersion")
    val knownStateVersion: String? = null,
    @SerialName("sdkVersion")
    val sdkVersion: String? = null,
    @SerialName("platform")
    val platform: String? = null,
    @SerialName("appId")
    val appId: String? = null,
    @SerialName("appVersion")
    val appVersion: String? = null,
    @SerialName("modelInventory")
    val modelInventory: List<ModelInventoryEntry> = emptyList(),
    @SerialName("activeVersions")
    val activeVersions: List<ActiveVersionEntry> = emptyList(),
    @SerialName("availableStorageBytes")
    val availableStorageBytes: Long? = null,
)

@Serializable
data class DeviceSyncResponse(
    @SerialName("schemaVersion")
    val schemaVersion: String = "1.12.0",
    @SerialName("deviceId")
    val deviceId: String,
    @SerialName("generatedAt")
    val generatedAt: String? = null,
    @SerialName("stateChanged")
    val stateChanged: Boolean = true,
    @SerialName("models")
    val models: List<DesiredModelEntry> = emptyList(),
    @SerialName("gcEligibleArtifactIds")
    val gcEligibleArtifactIds: List<String> = emptyList(),
    @SerialName("nextPollIntervalSeconds")
    val nextPollIntervalSeconds: Int = 60,
    @SerialName("serverTimestamp")
    val serverTimestamp: String? = null,
)
