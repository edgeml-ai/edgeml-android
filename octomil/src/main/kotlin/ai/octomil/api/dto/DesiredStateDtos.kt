package ai.octomil.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

// =========================================================================
// Device Sync
// =========================================================================

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
