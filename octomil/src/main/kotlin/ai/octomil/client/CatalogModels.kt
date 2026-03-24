package ai.octomil.client

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Per-variant device compatibility assessment from the catalog browse endpoint.
 */
@Serializable
data class BrowseCompatibility(
    val compatible: Boolean,
    @SerialName("device_class") val deviceClass: String,
    @SerialName("recommended_package_id") val recommendedPackageId: String? = null,
    @SerialName("total_size_bytes") val totalSizeBytes: Long? = null,
    val issues: List<String> = emptyList(),
) {
    /** Human-readable formatted size (e.g. "1.2 GB", "584 MB"). */
    val formattedSize: String
        get() {
            val bytes = totalSizeBytes ?: return "Unknown"
            return when {
                bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
                bytes >= 1_048_576 -> "%.0f MB".format(bytes / 1_048_576.0)
                else -> "%.0f KB".format(bytes / 1024.0)
            }
        }
}

/**
 * Browse manifest: family-keyed map from the catalog browse endpoint.
 *
 * The raw response is a JSON object keyed by family name. This wrapper
 * provides structured access to the hierarchy.
 */
data class BrowseManifest(
    val families: Map<String, BrowseFamily>,
)

@Serializable
data class BrowseFamily(
    val id: String,
    val vendor: String,
    val description: String? = null,
    @SerialName("task_taxonomy") val taskTaxonomy: List<String>? = null,
    val capabilities: List<String>? = null,
    val license: String? = null,
    @SerialName("homepage_url") val homepageUrl: String? = null,
    val variants: Map<String, BrowseVariant> = emptyMap(),
)

@Serializable
data class BrowseVariant(
    val id: String,
    @SerialName("parameter_count") val parameterCount: String,
    @SerialName("context_length") val contextLength: Int? = null,
    val capabilities: List<String>? = null,
    val quantizations: List<String>? = null,
    val compatibility: BrowseCompatibility? = null,
    val versions: Map<String, BrowseVersion> = emptyMap(),
)

@Serializable
data class BrowseVersion(
    val id: String,
    val version: String,
    val lifecycle: String,
    @SerialName("released_at") val releasedAt: String? = null,
    @SerialName("min_sdk_version") val minSdkVersion: String? = null,
    val packages: List<BrowsePackage> = emptyList(),
)

@Serializable
data class BrowsePackage(
    val id: String,
    val platform: String,
    @SerialName("artifact_format") val artifactFormat: String,
    @SerialName("runtime_executor") val runtimeExecutor: String,
    val quantization: String? = null,
    @SerialName("support_tier") val supportTier: String? = null,
    val capabilities: List<String>? = null,
    @SerialName("input_modalities") val inputModalities: List<String>? = null,
    @SerialName("output_modalities") val outputModalities: List<String>? = null,
    @SerialName("is_default") val isDefault: Boolean = false,
)
