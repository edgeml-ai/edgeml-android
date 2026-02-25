package ai.octomil.training

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/**
 * A cached gradient entry for offline/retry submission.
 */
@Serializable
data class GradientCacheEntry(
    /** Training round ID. */
    val roundId: String,
    /** Federation ID. */
    val federationId: String,
    /** Serialized weight delta as base64 (stored as String for JSON compatibility). */
    val weightDeltaBase64: String,
    /** Training metrics (loss, accuracy, etc.). */
    val metrics: Map<String, Double> = emptyMap(),
    /** Epoch-millis timestamp when the gradient was produced. */
    val timestamp: Long,
    /** Number of training samples used. */
    val sampleCount: Int,
    /** Whether this gradient has been successfully submitted to the server. */
    val submitted: Boolean = false,
)

/**
 * File-based cache for gradient deltas produced during federated training.
 *
 * When gradient upload fails (offline, timeout, server error), the gradient
 * is stored locally and retried when connectivity is restored.
 *
 * Follows the same file-per-entry pattern as [ai.octomil.sync.EventQueue].
 *
 * @param cacheDir Directory to store gradient JSON files.
 * @param json kotlinx.serialization Json instance.
 * @param ioDispatcher Dispatcher for file I/O.
 */
class GradientCache(
    private val cacheDir: File,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()

    init {
        cacheDir.mkdirs()
    }

    /**
     * Store a gradient entry to disk.
     *
     * If an entry with the same [GradientCacheEntry.roundId] already exists,
     * it is overwritten.
     */
    suspend fun store(entry: GradientCacheEntry): Boolean =
        withContext(ioDispatcher) {
            mutex.withLock {
                try {
                    val file = File(cacheDir, "${entry.roundId}.json")
                    file.writeText(json.encodeToString(entry))
                    Timber.d("Gradient cached for round ${entry.roundId}")
                    true
                } catch (e: Exception) {
                    Timber.e(e, "Failed to cache gradient for round ${entry.roundId}")
                    false
                }
            }
        }

    /**
     * Retrieve a cached gradient by round ID.
     *
     * @return The entry, or null if not found or corrupt.
     */
    suspend fun get(roundId: String): GradientCacheEntry? =
        withContext(ioDispatcher) {
            mutex.withLock {
                try {
                    val file = File(cacheDir, "$roundId.json")
                    if (!file.exists()) return@withContext null
                    json.decodeFromString<GradientCacheEntry>(file.readText())
                } catch (e: Exception) {
                    Timber.w(e, "Failed to read cached gradient for round $roundId")
                    null
                }
            }
        }

    /**
     * List all pending (unsubmitted) gradient entries, sorted by timestamp.
     */
    suspend fun listPending(): List<GradientCacheEntry> =
        withContext(ioDispatcher) {
            mutex.withLock {
                try {
                    cacheDir
                        .listFiles()
                        ?.filter { it.extension == "json" }
                        ?.mapNotNull { file ->
                            try {
                                json.decodeFromString<GradientCacheEntry>(file.readText())
                            } catch (e: Exception) {
                                Timber.w(e, "Skipping corrupt gradient file: ${file.name}")
                                null
                            }
                        }
                        ?.filter { !it.submitted }
                        ?.sortedBy { it.timestamp }
                        ?: emptyList()
                } catch (e: Exception) {
                    Timber.e(e, "Failed to list pending gradients")
                    emptyList()
                }
            }
        }

    /**
     * Mark a gradient entry as submitted.
     *
     * @return true if the entry was found and updated.
     */
    suspend fun markSubmitted(roundId: String): Boolean =
        withContext(ioDispatcher) {
            mutex.withLock {
                try {
                    val file = File(cacheDir, "$roundId.json")
                    if (!file.exists()) return@withContext false
                    val entry = json.decodeFromString<GradientCacheEntry>(file.readText())
                    val updated = entry.copy(submitted = true)
                    file.writeText(json.encodeToString(updated))
                    Timber.d("Gradient marked submitted for round $roundId")
                    true
                } catch (e: Exception) {
                    Timber.w(e, "Failed to mark gradient submitted for round $roundId")
                    false
                }
            }
        }

    /**
     * Purge entries older than [maxAgeMs] milliseconds.
     *
     * @param maxAgeMs Maximum age in milliseconds. Defaults to 24 hours.
     * @param now Current time in epoch millis (injectable for testing).
     * @return Number of entries purged.
     */
    suspend fun purgeOlderThan(
        maxAgeMs: Long = 24 * 60 * 60 * 1000L,
        now: Long = System.currentTimeMillis(),
    ): Int =
        withContext(ioDispatcher) {
            mutex.withLock {
                var purged = 0
                try {
                    cacheDir.listFiles()
                        ?.filter { it.extension == "json" }
                        ?.forEach { file ->
                            try {
                                val entry = json.decodeFromString<GradientCacheEntry>(file.readText())
                                if (now - entry.timestamp > maxAgeMs) {
                                    if (file.delete()) purged++
                                }
                            } catch (e: Exception) {
                                // Corrupt file — delete it
                                if (file.delete()) purged++
                            }
                        }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to purge old gradients")
                }
                Timber.d("Purged $purged old gradient entries")
                purged
            }
        }

    /**
     * Get the total number of cached entries (submitted + unsubmitted).
     */
    suspend fun size(): Int =
        withContext(ioDispatcher) {
            mutex.withLock {
                cacheDir.listFiles()?.count { it.extension == "json" } ?: 0
            }
        }

    /**
     * Clear all cached gradient entries.
     */
    suspend fun clear() =
        withContext(ioDispatcher) {
            mutex.withLock {
                cacheDir.listFiles()?.forEach { file ->
                    if (!file.delete()) {
                        Timber.w("Failed to delete gradient file: ${file.name}")
                    }
                }
                Timber.d("Gradient cache cleared")
            }
        }

    companion object {
        private const val CACHE_DIR_NAME = "octomil_gradient_cache"

        /**
         * Create a GradientCache using the app's internal files directory.
         */
        fun create(filesDir: File): GradientCache {
            val cacheDir = File(filesDir, CACHE_DIR_NAME)
            return GradientCache(cacheDir)
        }

        /**
         * Create a GradientCache for testing with injected dependencies.
         */
        @androidx.annotation.VisibleForTesting
        internal fun createForTesting(
            cacheDir: File,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): GradientCache {
            cacheDir.mkdirs()
            val json = Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
            return GradientCache(cacheDir, json, ioDispatcher)
        }
    }
}
