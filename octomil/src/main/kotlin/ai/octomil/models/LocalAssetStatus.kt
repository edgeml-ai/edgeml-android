package ai.octomil.models

import java.io.File

/**
 * Describes the readiness of a local model asset.
 *
 * Use this to determine what action is needed before inference can proceed.
 * The lifecycle is idempotent: calling [ModelManager.checkAssetStatus] a second
 * time after a successful download returns [Ready] without re-downloading.
 *
 * Lifecycle:
 * - **First run**: returns [DownloadRequired] with URL and size for download UI.
 * - **Second run**: returns [Ready] immediately from cache (no network call).
 * - **In-progress**: returns [Preparing] if a download is active.
 * - **Offline/error**: returns [Unavailable] with an actionable reason string.
 */
sealed class LocalAssetStatus {

    /**
     * Model is cached locally, verified, and ready for inference.
     *
     * @property localFile The cached model file on disk.
     * @property model The cached model metadata.
     */
    data class Ready(
        val localFile: File,
        val model: CachedModel,
    ) : LocalAssetStatus()

    /**
     * Model must be downloaded before use.
     *
     * @property url The download source URL.
     * @property sizeBytes Expected file size in bytes (0 if unknown).
     */
    data class DownloadRequired(
        val url: String,
        val sizeBytes: Long,
    ) : LocalAssetStatus()

    /**
     * A download or verification is currently in progress for this model.
     *
     * @property progress Download progress (0.0 to 1.0), or null if indeterminate.
     */
    data class Preparing(
        val progress: Float? = null,
    ) : LocalAssetStatus()

    /**
     * The model cannot be prepared locally.
     *
     * @property reason A human-readable, actionable explanation. Never contains
     *   secrets, tokens, or server URLs.
     */
    data class Unavailable(
        val reason: String,
    ) : LocalAssetStatus()

    // -------------------------------------------------------------------------
    // Convenience accessors
    // -------------------------------------------------------------------------

    /** Whether the model is ready for inference without any further work. */
    val isReady: Boolean get() = this is Ready

    /** Whether a download is needed before inference. */
    val needsDownload: Boolean get() = this is DownloadRequired

    /** Whether work is currently in progress to prepare this model. */
    val isPreparing: Boolean get() = this is Preparing

    /** The local file, if the model is ready. Null otherwise. */
    val resolvedFile: File? get() = (this as? Ready)?.localFile

    /** Human-readable status description for logging and debugging. */
    val statusDescription: String
        get() = when (this) {
            is Ready -> "Ready at ${localFile.name}"
            is DownloadRequired -> {
                val mb = sizeBytes / (1024.0 * 1024.0)
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
