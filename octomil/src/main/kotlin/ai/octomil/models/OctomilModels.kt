package ai.octomil.models

import timber.log.Timber

/**
 * Models namespace -- model lifecycle operations.
 *
 * Matches the SDK Facade Contract `models` namespace:
 * - `status(modelId)` -> [ModelStatus]
 * - `load(modelId, version?)` -> [CachedModel]
 * - `unload(modelId)` -> void
 * - `list()` -> List<[CachedModel]>
 * - `clearCache()` -> void
 */
class OctomilModels(private val modelManager: ModelManager) {

    /** Set of model IDs currently "loaded" (held in runtime memory). */
    private val loadedModels = mutableSetOf<String>()

    /**
     * Query the current status of a model.
     *
     * Checks in-memory download state first (for active downloads / errors),
     * then falls back to the on-disk cache metadata.
     */
    fun status(modelId: String): ModelStatus {
        val downloadState = modelManager.currentDownloadState

        return when (downloadState) {
            is DownloadState.Downloading -> {
                if (downloadState.progress.modelId == modelId) {
                    ModelStatus.DOWNLOADING
                } else {
                    checkCacheStatus(modelId)
                }
            }
            is DownloadState.CheckingForUpdates -> {
                // CheckingForUpdates doesn't carry a modelId; fall through to cache.
                checkCacheStatus(modelId)
            }
            is DownloadState.Verifying -> {
                // Verification is part of the download pipeline.
                ModelStatus.DOWNLOADING
            }
            is DownloadState.Failed -> {
                ModelStatus.FAILED
            }
            is DownloadState.Completed -> {
                if (downloadState.model.modelId == modelId) {
                    ModelStatus.READY
                } else {
                    checkCacheStatus(modelId)
                }
            }
            is DownloadState.UpToDate -> {
                if (downloadState.model.modelId == modelId) {
                    ModelStatus.READY
                } else {
                    checkCacheStatus(modelId)
                }
            }
            is DownloadState.Idle -> {
                checkCacheStatus(modelId)
            }
        }
    }

    /**
     * Download a model if not cached, then return the [CachedModel].
     *
     * @param modelId Model identifier.
     * @param version Specific version to load; null resolves the latest.
     * @return The locally cached model, ready for inference.
     * @throws ModelDownloadException if download or verification fails.
     */
    suspend fun load(modelId: String, version: String? = null): CachedModel {
        // If already cached with the requested version, return it directly
        val existing = modelManager.getCachedModel(modelId, version)
        if (existing != null && existing.isValid()) {
            loadedModels.add(modelId)
            modelManager.touchModel(modelId, existing.version)
            Timber.d("Model $modelId already cached, returning existing")
            return existing
        }

        // Download (ensureModelAvailable resolves the latest version from the server)
        val result = modelManager.ensureModelAvailable(modelId = modelId)
        val cachedModel = result.getOrThrow()
        loadedModels.add(modelId)
        return cachedModel
    }

    /**
     * Release a model from runtime memory.
     *
     * Does NOT remove it from the disk cache -- use [clearCache] for that.
     */
    fun unload(modelId: String) {
        loadedModels.remove(modelId)
        Timber.d("Model $modelId unloaded from runtime")
    }

    /**
     * List all locally cached models.
     */
    suspend fun list(): List<CachedModel> {
        val stats = modelManager.getCacheStats()
        return stats.models
    }

    /**
     * Remove all cached models from disk.
     */
    suspend fun clearCache() {
        modelManager.clearCache()
        loadedModels.clear()
        Timber.d("Model cache cleared")
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /**
     * Check the in-memory cache metadata for a model.
     *
     * Uses the non-suspending [ModelManager.hasCachedModel] to peek at the
     * in-memory metadata map without acquiring the mutex.
     */
    private fun checkCacheStatus(modelId: String): ModelStatus {
        return if (modelManager.hasCachedModel(modelId)) {
            ModelStatus.READY
        } else {
            ModelStatus.NOT_CACHED
        }
    }
}
