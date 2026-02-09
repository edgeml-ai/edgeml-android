package ai.edgeml.models

import ai.edgeml.api.EdgeMLApi
import ai.edgeml.config.EdgeMLConfig
import ai.edgeml.storage.SecureStorage
import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Manages ML model downloads, caching, and lifecycle.
 *
 * Features:
 * - Automatic model download and caching
 * - Integrity verification via SHA-256 checksums
 * - LRU cache eviction when storage limits are exceeded
 * - Progress tracking for downloads
 * - Offline support with cached models
 */
class ModelManager(
    private val context: Context,
    private val config: EdgeMLConfig,
    private val api: EdgeMLApi,
    private val storage: SecureStorage,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val cacheDir = File(context.cacheDir, "edgeml_models")
    private val metadataFile = File(cacheDir, "cache_metadata.json")
    private val mutex = Mutex()
    private val downloadClient: OkHttpClient

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: Flow<DownloadState> = _downloadState.asStateFlow()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // In-memory cache metadata
    private var cacheMetadata: MutableMap<String, CachedModel> = mutableMapOf()

    init {
        // Ensure cache directory exists
        cacheDir.mkdirs()

        // Create download client with longer timeouts for large models
        downloadClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(10, TimeUnit.MINUTES)
            .build()

        // Load cache metadata
        loadCacheMetadata()
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Ensure the latest model is available locally.
     *
     * Downloads if not cached or if a newer version is available.
     *
     * @param modelId Model identifier
     * @param forceDownload If true, download even if cached
     * @return The cached model
     */
    suspend fun ensureModelAvailable(
        modelId: String = config.modelId,
        forceDownload: Boolean = false,
    ): Result<CachedModel> = withContext(ioDispatcher) {
        mutex.withLock {
            try {
                _downloadState.value = DownloadState.CheckingForUpdates

                val version = resolveVersion(modelId)
                val cacheKey = getCacheKey(modelId, version)

                // Check if we already have this version
                val cachedModel = cacheMetadata[cacheKey]
                if (!forceDownload && cachedModel != null && cachedModel.isValid()) {
                    Timber.d("Model $modelId v$version already cached")
                    storage.setCurrentModelVersion(version)
                    _downloadState.value = DownloadState.UpToDate(cachedModel)
                    return@withContext Result.success(cachedModel)
                }

                val downloadInfo = fetchDownloadUrl(modelId, version)

                // Download the model
                val modelFile = downloadModel(
                    downloadUrl = downloadInfo.downloadUrl,
                    modelId = modelId,
                    version = version,
                    expectedChecksum = downloadInfo.checksum,
                    expectedSize = downloadInfo.sizeBytes,
                )

                // Create cached model entry
                val newCachedModel = CachedModel(
                    modelId = modelId,
                    version = version,
                    filePath = modelFile.absolutePath,
                    checksum = downloadInfo.checksum,
                    sizeBytes = downloadInfo.sizeBytes,
                    format = "tensorflow_lite",
                    downloadedAt = System.currentTimeMillis(),
                    verified = true,
                )

                // Update cache
                cacheMetadata[cacheKey] = newCachedModel
                saveCacheMetadata()
                storage.setCurrentModelVersion(version)
                storage.setModelChecksum(downloadInfo.checksum)

                evictOldModels()

                _downloadState.value = DownloadState.Completed(newCachedModel)
                Result.success(newCachedModel)
            } catch (e: ModelDownloadException) {
                Timber.e(e, "Model download failed")
                _downloadState.value = DownloadState.Failed(e)
                Result.failure(e)
            } catch (e: Exception) {
                Timber.e(e, "Unexpected error during model download")
                val downloadException = ModelDownloadException(
                    "Download failed: ${e.message}",
                    cause = e,
                    errorCode = ModelDownloadException.ErrorCode.UNKNOWN
                )
                _downloadState.value = DownloadState.Failed(downloadException)
                Result.failure(downloadException)
            }
        }
    }

    private suspend fun resolveVersion(modelId: String): String {
        val versionResponse = api.getDeviceVersion(
            deviceId = getDeviceId(),
            modelId = modelId,
        )

        if (!versionResponse.isSuccessful) {
            throw ModelDownloadException(
                "Failed to get version: ${versionResponse.code()}",
                errorCode = when (versionResponse.code()) {
                    401, 403 -> ModelDownloadException.ErrorCode.UNAUTHORIZED
                    404 -> ModelDownloadException.ErrorCode.NOT_FOUND
                    in 500..599 -> ModelDownloadException.ErrorCode.SERVER_ERROR
                    else -> ModelDownloadException.ErrorCode.NETWORK_ERROR
                }
            )
        }

        val resolvedVersion = versionResponse.body()
            ?: throw ModelDownloadException("Empty version response")
        return resolvedVersion.version
    }

    private suspend fun fetchDownloadUrl(
        modelId: String,
        version: String,
    ): ai.edgeml.api.dto.ModelDownloadResponse {
        val downloadResponse = api.getModelDownloadUrl(
            modelId = modelId,
            version = version,
            format = "tensorflow_lite"
        )

        if (!downloadResponse.isSuccessful) {
            throw ModelDownloadException(
                "Failed to get download URL: ${downloadResponse.code()}",
                errorCode = ModelDownloadException.ErrorCode.NETWORK_ERROR
            )
        }

        return downloadResponse.body()
            ?: throw ModelDownloadException("Empty download response")
    }

    /**
     * Get a cached model by ID and version.
     *
     * @param modelId Model identifier
     * @param version Version string (null for latest cached)
     * @return Cached model or null if not found
     */
    suspend fun getCachedModel(
        modelId: String = config.modelId,
        version: String? = null,
    ): CachedModel? = withContext(ioDispatcher) {
        mutex.withLock {
            if (version != null) {
                val cacheKey = getCacheKey(modelId, version)
                cacheMetadata[cacheKey]?.takeIf { it.isValid() }
            } else {
                // Return the most recent cached version
                cacheMetadata.values
                    .filter { it.modelId == modelId && it.isValid() }
                    .maxByOrNull { it.downloadedAt }
            }
        }
    }

    /**
     * Get cache statistics.
     */
    suspend fun getCacheStats(): CacheStats = withContext(ioDispatcher) {
        mutex.withLock {
            val models = cacheMetadata.values.toList()
            val totalSize = models.sumOf { it.sizeBytes }
            val mostRecent = models.maxByOrNull { it.lastUsedAt }

            CacheStats(
                modelCount = models.size,
                totalSizeBytes = totalSize,
                cacheSizeLimitBytes = config.modelCacheSizeBytes,
                mostRecentModel = mostRecent,
                models = models,
            )
        }
    }

    /**
     * Delete a specific cached model.
     */
    suspend fun deleteModel(modelId: String, version: String): Boolean = withContext(ioDispatcher) {
        mutex.withLock {
            val cacheKey = getCacheKey(modelId, version)
            val cachedModel = cacheMetadata.remove(cacheKey)

            if (cachedModel != null) {
                if (!File(cachedModel.filePath).delete()) {
                    Timber.w("Failed to delete model file: ${cachedModel.filePath}")
                }
                saveCacheMetadata()
                true
            } else {
                false
            }
        }
    }

    /**
     * Clear all cached models.
     */
    suspend fun clearCache() = withContext(ioDispatcher) {
        mutex.withLock {
            cacheMetadata.values.forEach { model ->
                if (!File(model.filePath).delete()) {
                    Timber.w("Failed to delete model file: ${model.filePath}")
                }
            }
            cacheMetadata.clear()
            saveCacheMetadata()
        }
    }

    /**
     * Update the last used timestamp for a model.
     */
    suspend fun touchModel(modelId: String, version: String) = withContext(ioDispatcher) {
        mutex.withLock {
            val cacheKey = getCacheKey(modelId, version)
            cacheMetadata[cacheKey]?.let { model ->
                cacheMetadata[cacheKey] = model.copy(lastUsedAt = System.currentTimeMillis())
                saveCacheMetadata()
            }
        }
    }

    // =========================================================================
    // Private Implementation
    // =========================================================================

    private fun getCacheKey(modelId: String, version: String): String = "${modelId}_$version"

    private suspend fun getDeviceId(): String {
        return storage.getServerDeviceId()
            ?: throw ModelDownloadException(
                "Device not registered",
                errorCode = ModelDownloadException.ErrorCode.UNAUTHORIZED
            )
    }

    private suspend fun downloadModel(
        downloadUrl: String,
        modelId: String,
        version: String,
        expectedChecksum: String,
        expectedSize: Long,
    ): File = withContext(ioDispatcher) {
        val modelFile = File(cacheDir, "${modelId}_$version.tflite")

        // Check available storage
        val availableSpace = cacheDir.usableSpace
        if (availableSpace < expectedSize * 2) {
            throw ModelDownloadException(
                "Insufficient storage: need ${expectedSize * 2} bytes, have $availableSpace",
                errorCode = ModelDownloadException.ErrorCode.INSUFFICIENT_STORAGE
            )
        }

        val request = Request.Builder()
            .url(downloadUrl)
            .build()

        val response = downloadClient.newCall(request).execute()

        if (!response.isSuccessful) {
            throw ModelDownloadException(
                "Download failed: ${response.code}",
                errorCode = ModelDownloadException.ErrorCode.NETWORK_ERROR
            )
        }

        val body = response.body
            ?: throw ModelDownloadException(
                "Empty response body",
                errorCode = ModelDownloadException.ErrorCode.NETWORK_ERROR
            )

        val totalBytes = body.contentLength().takeIf { it > 0 } ?: expectedSize
        val tempFile = File(cacheDir, "${modelId}_$version.tmp")

        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var bytesDownloaded = 0L

            FileOutputStream(tempFile).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        digest.update(buffer, 0, bytesRead)
                        bytesDownloaded += bytesRead

                        _downloadState.value = DownloadState.Downloading(
                            DownloadProgress(
                                modelId = modelId,
                                version = version,
                                bytesDownloaded = bytesDownloaded,
                                totalBytes = totalBytes,
                            )
                        )
                    }
                }
            }

            verifyChecksum(digest, expectedChecksum, tempFile)
            finalizeDownload(tempFile, modelFile)

            Timber.i("Model downloaded successfully: ${modelFile.absolutePath}")
            modelFile
        } catch (e: Exception) {
            tempFile.delete()
            if (e is ModelDownloadException) throw e
            throw ModelDownloadException(
                "IO error during download: ${e.message}",
                cause = e,
                errorCode = ModelDownloadException.ErrorCode.IO_ERROR
            )
        }
    }

    private fun verifyChecksum(
        digest: MessageDigest,
        expectedChecksum: String,
        tempFile: File,
    ) {
        _downloadState.value = DownloadState.Verifying
        val actualChecksum = digest.digest().joinToString("") { "%02x".format(it) }

        if (!actualChecksum.equals(expectedChecksum, ignoreCase = true)) {
            if (!tempFile.delete()) {
                Timber.w("Failed to delete temp file after checksum mismatch: ${tempFile.name}")
            }
            throw ModelDownloadException(
                "Checksum mismatch: expected $expectedChecksum, got $actualChecksum",
                errorCode = ModelDownloadException.ErrorCode.CHECKSUM_MISMATCH
            )
        }
    }

    private fun finalizeDownload(tempFile: File, modelFile: File) {
        if (modelFile.exists() && !modelFile.delete()) {
            Timber.w("Failed to delete existing model file: ${modelFile.name}")
        }
        if (!tempFile.renameTo(modelFile)) {
            tempFile.copyTo(modelFile, overwrite = true)
            if (!tempFile.delete()) {
                Timber.w("Failed to delete temp file after copy: ${tempFile.name}")
            }
        }
    }

    private fun loadCacheMetadata() {
        try {
            if (metadataFile.exists()) {
                val jsonString = metadataFile.readText()
                val metadata: Map<String, CachedModel> = json.decodeFromString(jsonString)
                cacheMetadata = metadata.toMutableMap()
                Timber.d("Loaded ${cacheMetadata.size} cached models")
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to load cache metadata")
            cacheMetadata = mutableMapOf()
        }
    }

    private fun saveCacheMetadata() {
        try {
            val jsonString = json.encodeToString(cacheMetadata.toMap())
            metadataFile.writeText(jsonString)
        } catch (e: Exception) {
            Timber.e(e, "Failed to save cache metadata")
        }
    }

    private fun evictOldModels() {
        val totalSize = cacheMetadata.values.sumOf { it.sizeBytes }
        val limit = config.modelCacheSizeBytes

        if (totalSize <= limit) return

        // Sort by last used time (LRU)
        val sortedModels = cacheMetadata.values.sortedBy { it.lastUsedAt }
        var currentSize = totalSize

        for (model in sortedModels) {
            if (currentSize <= limit) break

            val cacheKey = getCacheKey(model.modelId, model.version)
            if (!File(model.filePath).delete()) {
                Timber.w("Failed to delete evicted model file: ${model.filePath}")
            }
            cacheMetadata.remove(cacheKey)
            currentSize -= model.sizeBytes
            Timber.d("Evicted model ${model.modelId} v${model.version}")
        }

        saveCacheMetadata()
    }
}
