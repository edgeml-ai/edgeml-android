package ai.octomil.api.dto

import ai.octomil.models.ServerModelContract
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
