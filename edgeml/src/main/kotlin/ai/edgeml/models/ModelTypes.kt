package ai.edgeml.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Represents a locally cached ML model.
 */
@Serializable
data class CachedModel(
    /** Model identifier */
    @SerialName("model_id")
    val modelId: String,

    /** Model version string (semantic versioning) */
    @SerialName("version")
    val version: String,

    /** Path to the model file on disk */
    @SerialName("file_path")
    val filePath: String,

    /** SHA-256 checksum for integrity verification */
    @SerialName("checksum")
    val checksum: String,

    /** Size of the model file in bytes */
    @SerialName("size_bytes")
    val sizeBytes: Long,

    /** Model format (e.g., tensorflow_lite, onnx) */
    @SerialName("format")
    val format: String,

    /** Timestamp when the model was downloaded */
    @SerialName("downloaded_at")
    val downloadedAt: Long,

    /** Last time the model was used for inference */
    @SerialName("last_used_at")
    val lastUsedAt: Long = downloadedAt,

    /** Whether the model has been verified */
    @SerialName("verified")
    val verified: Boolean = false,
) {
    /**
     * Get the model file.
     */
    fun getFile(): File = File(filePath)

    /**
     * Check if the model file exists.
     */
    fun exists(): Boolean = getFile().exists()

    /**
     * Check if the model is valid (exists and verified).
     */
    fun isValid(): Boolean = exists() && verified
}

/**
 * Model download progress.
 */
data class DownloadProgress(
    /** Model ID being downloaded */
    val modelId: String,

    /** Version being downloaded */
    val version: String,

    /** Bytes downloaded so far */
    val bytesDownloaded: Long,

    /** Total bytes to download */
    val totalBytes: Long,

    /** Progress percentage (0-100) */
    val progress: Int = if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt() else 0,
)

/**
 * Model download state.
 */
sealed class DownloadState {
    /** Download not started */
    data object Idle : DownloadState()

    /** Checking for updates */
    data object CheckingForUpdates : DownloadState()

    /** Download in progress */
    data class Downloading(val progress: DownloadProgress) : DownloadState()

    /** Verifying downloaded model */
    data object Verifying : DownloadState()

    /** Download completed successfully */
    data class Completed(val model: CachedModel) : DownloadState()

    /** Download failed */
    data class Failed(val error: ModelDownloadException) : DownloadState()

    /** Model is up to date (no download needed) */
    data class UpToDate(val model: CachedModel) : DownloadState()
}

/**
 * Exception thrown when model download fails.
 */
class ModelDownloadException(
    message: String,
    cause: Throwable? = null,
    val errorCode: ErrorCode = ErrorCode.UNKNOWN,
) : Exception(message, cause) {

    enum class ErrorCode {
        NETWORK_ERROR,
        NOT_FOUND,
        CHECKSUM_MISMATCH,
        INSUFFICIENT_STORAGE,
        IO_ERROR,
        UNAUTHORIZED,
        SERVER_ERROR,
        UNKNOWN,
    }
}

/**
 * Inference input data.
 */
data class InferenceInput(
    /** Input tensor data as float array */
    val data: FloatArray,

    /** Input tensor shape */
    val shape: IntArray,

    /** Optional input name (for multi-input models) */
    val name: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as InferenceInput

        if (!data.contentEquals(other.data)) return false
        if (!shape.contentEquals(other.shape)) return false
        if (name != other.name) return false

        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + shape.contentHashCode()
        result = 31 * result + (name?.hashCode() ?: 0)
        return result
    }
}

/**
 * Inference output result.
 */
data class InferenceOutput(
    /** Output tensor data as float array */
    val data: FloatArray,

    /** Output tensor shape */
    val shape: IntArray,

    /** Inference time in milliseconds */
    val inferenceTimeMs: Long,

    /** Optional output name (for multi-output models) */
    val name: String? = null,
) {
    /**
     * Get the index of the maximum value (for classification).
     */
    fun argmax(): Int = data.indices.maxByOrNull { data[it] } ?: -1

    /**
     * Get top-k indices sorted by confidence.
     */
    fun topK(k: Int): List<Pair<Int, Float>> =
        data.withIndex()
            .sortedByDescending { it.value }
            .take(k)
            .map { it.index to it.value }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as InferenceOutput

        if (!data.contentEquals(other.data)) return false
        if (!shape.contentEquals(other.shape)) return false
        if (inferenceTimeMs != other.inferenceTimeMs) return false
        if (name != other.name) return false

        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + shape.contentHashCode()
        result = 31 * result + inferenceTimeMs.hashCode()
        result = 31 * result + (name?.hashCode() ?: 0)
        return result
    }
}

/**
 * Model cache statistics.
 */
data class CacheStats(
    /** Total number of cached models */
    val modelCount: Int,

    /** Total size of cached models in bytes */
    val totalSizeBytes: Long,

    /** Cache size limit in bytes */
    val cacheSizeLimitBytes: Long,

    /** Most recently used model */
    val mostRecentModel: CachedModel?,

    /** List of all cached models */
    val models: List<CachedModel>,
) {
    /** Usage percentage (0-100) */
    val usagePercent: Int = if (cacheSizeLimitBytes > 0) {
        ((totalSizeBytes * 100) / cacheSizeLimitBytes).toInt()
    } else 0

    /** Available space in bytes */
    val availableBytes: Long = (cacheSizeLimitBytes - totalSizeBytes).coerceAtLeast(0)
}
