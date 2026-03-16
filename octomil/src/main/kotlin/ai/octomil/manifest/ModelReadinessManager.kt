package ai.octomil.manifest

import ai.octomil.generated.ModelCapability
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.io.File

/**
 * Progress update for a managed model download.
 */
data class DownloadUpdate(
    val modelId: String,
    val capability: ModelCapability,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val complete: Boolean,
    val error: Throwable? = null,
) {
    val progress: Float
        get() = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f
}

/**
 * Orchestrates managed model downloads and tracks readiness.
 *
 * [ModelCatalogService] enqueues entries; the readiness manager exposes
 * download progress as a [StateFlow] and provides blocking [awaitReady]
 * for callers that need to wait until a capability is available.
 *
 * The actual download is delegated to [ai.octomil.models.ModelManager] when
 * a server-connected config is available. For local-only setups, the app
 * is expected to side-load model files and call [markReady] directly.
 */
class ModelReadinessManager(
    private val context: Context,
) {
    private val _downloadUpdates = MutableStateFlow<List<DownloadUpdate>>(emptyList())

    /** Observe download progress for all managed models. */
    val downloadUpdates: StateFlow<List<DownloadUpdate>> = _downloadUpdates.asStateFlow()

    private val readyFiles = mutableMapOf<ModelCapability, File>()
    private val pendingEntries = mutableListOf<AppModelEntry>()

    /** Enqueue a managed model entry for download. */
    internal fun enqueue(entry: AppModelEntry) {
        pendingEntries.add(entry)
        Timber.d("Enqueued managed model for download: ${entry.id} (${entry.capability})")
    }

    /** Whether a runtime for the given capability is ready. */
    fun isReady(capability: ModelCapability): Boolean =
        readyFiles.containsKey(capability)

    /**
     * Suspend until a runtime for the given capability is available.
     *
     * Returns immediately if the model is already ready.
     */
    suspend fun awaitReady(capability: ModelCapability) {
        if (isReady(capability)) return

        // Wait for a download update that marks this capability as complete
        _downloadUpdates.first { updates ->
            updates.any { it.capability == capability && it.complete && it.error == null }
        }
    }

    /** Get the resolved file for a capability, or null if not yet ready. */
    internal fun resolvedFile(capability: ModelCapability): File? =
        readyFiles[capability]

    /**
     * Mark a capability as ready with the given file.
     *
     * Called by download completion callbacks or by the app for side-loaded models.
     */
    fun markReady(capability: ModelCapability, file: File) {
        readyFiles[capability] = file
        Timber.d("Model ready for capability $capability: ${file.name}")

        // Find matching entry and emit completion update
        val entry = pendingEntries.firstOrNull { it.capability == capability }
        if (entry != null) {
            val update = DownloadUpdate(
                modelId = entry.id,
                capability = capability,
                bytesDownloaded = file.length(),
                totalBytes = file.length(),
                complete = true,
            )
            _downloadUpdates.value = _downloadUpdates.value + update
        }
    }

    /**
     * Report download progress for a managed model.
     */
    fun reportProgress(
        modelId: String,
        capability: ModelCapability,
        bytesDownloaded: Long,
        totalBytes: Long,
    ) {
        val update = DownloadUpdate(
            modelId = modelId,
            capability = capability,
            bytesDownloaded = bytesDownloaded,
            totalBytes = totalBytes,
            complete = false,
        )
        _downloadUpdates.value = _downloadUpdates.value
            .filter { it.modelId != modelId || it.complete } + update
    }

    /**
     * Report a download failure.
     */
    fun reportError(modelId: String, capability: ModelCapability, error: Throwable) {
        val update = DownloadUpdate(
            modelId = modelId,
            capability = capability,
            bytesDownloaded = 0,
            totalBytes = 0,
            complete = true,
            error = error,
        )
        _downloadUpdates.value = _downloadUpdates.value + update
        Timber.w(error, "Download failed for model: $modelId")
    }
}
