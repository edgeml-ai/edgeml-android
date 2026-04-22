package ai.octomil.models

import java.io.File

/**
 * Describes the readiness of a local model asset.
 *
 * Use this to determine what action is needed before inference can proceed.
 * The lifecycle is idempotent: calling [ModelManager.checkAssetStatus] a second
 * time after a successful download returns [Ready] without re-downloading.
 *
 * Status values:
 * - [Ready] — The model is cached locally, verified, and ready for inference.
 *   Second-run behavior: returns immediately from cache.
 * - [DownloadRequired] — The model is not cached. The URL and size are provided
 *   so the caller can display download UI or decide whether to proceed.
 * - [Preparing] — A download or verification is in progress for this model.
 * - [Unavailable] — The model cannot be made available locally (e.g. no network,
 *   model not found on server, unsupported format).
 */
sealed class LocalAssetStatus {

    /**
     * Model is cached and ready for inference.
     *
     * @property localFile The verified model file on disk.
     * @property cachedModel Full cached model metadata.
     */
    data class Ready(
        val localFile: File,
        val cachedModel: CachedModel,
    ) : LocalAssetStatus()

    /**
     * Model must be downloaded before use.
     *
     * @property sizeBytes Expected file size in bytes.
     * @property modelId Model identifier.
     * @property version Model version.
     */
    data class DownloadRequired(
        val sizeBytes: Long,
        val modelId: String,
        val version: String,
    ) : LocalAssetStatus()

    /**
     * A download or verification is currently in progress.
     *
     * @property progress Download progress (0.0–1.0), or null if indeterminate.
     */
    data class Preparing(
        val progress: Double? = null,
    ) : LocalAssetStatus()

    /**
     * The model cannot be prepared locally.
     *
     * @property reason Human-readable, actionable explanation.
     */
    data class Unavailable(
        val reason: String,
    ) : LocalAssetStatus()

    /** Whether the model is ready for inference without any further work. */
    val isReady: Boolean get() = this is Ready

    /** Whether a download is needed before inference. */
    val needsDownload: Boolean get() = this is DownloadRequired

    /** Whether work is currently in progress to prepare this model. */
    val isPreparing: Boolean get() = this is Preparing

    /** The local file, if the model is ready. Null otherwise. */
    val modelFile: File?
        get() = (this as? Ready)?.localFile

    /** Human-readable description for logging and debugging. */
    val statusDescription: String
        get() = when (this) {
            is Ready -> "Ready at ${localFile.name}"
            is DownloadRequired -> {
                val mb = sizeBytes.toDouble() / (1024 * 1024)
                "Download required (%.1f MB)".format(mb)
            }
            is Preparing -> if (progress != null) {
                "Preparing (%.0f%%)".format(progress * 100)
            } else {
                "Preparing..."
            }
            is Unavailable -> "Unavailable: $reason"
        }
}
