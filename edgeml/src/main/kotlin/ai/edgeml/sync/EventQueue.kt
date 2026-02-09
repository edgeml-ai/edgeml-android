package ai.edgeml.sync

import android.content.Context
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
import java.util.UUID

/**
 * Queue for offline event storage.
 *
 * Stores training events and metrics when offline for later sync.
 */
class EventQueue private constructor(
    private val queueDir: File,
    private val json: Json,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private val maxQueueSize = 1000

    companion object {
        private const val QUEUE_DIR_NAME = "edgeml_event_queue"

        @Volatile
        private var instance: EventQueue? = null

        fun getInstance(context: Context): EventQueue =
            instance ?: synchronized(this) {
                instance ?: createInstance(context.applicationContext).also {
                    instance = it
                }
            }

        private fun createInstance(context: Context): EventQueue {
            val queueDir = File(context.filesDir, QUEUE_DIR_NAME)
            queueDir.mkdirs()

            val json =
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                }

            return EventQueue(queueDir, json, Dispatchers.IO)
        }

        /**
         * Create an EventQueue instance for testing with injected dependencies.
         */
        @androidx.annotation.VisibleForTesting
        internal fun createForTesting(
            queueDir: File,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): EventQueue {
            queueDir.mkdirs()
            val json =
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                }
            return EventQueue(queueDir, json, ioDispatcher)
        }
    }

    /**
     * Add an event to the queue.
     *
     * @param event The queued event to add
     * @return True if added successfully
     */
    suspend fun addEvent(event: QueuedEvent): Boolean =
        withContext(ioDispatcher) {
            mutex.withLock {
                try {
                    // Check queue size
                    val existingEvents = queueDir.listFiles()?.size ?: 0
                    if (existingEvents >= maxQueueSize) {
                        // Remove oldest event
                        val oldest =
                            queueDir
                                .listFiles()
                                ?.minByOrNull { it.lastModified() }
                        if (oldest?.delete() == false) {
                            Timber.w("Failed to delete oldest event file: ${oldest.name}")
                        }
                    }

                    // Write event to file
                    val eventFile = File(queueDir, "${event.id}.json")
                    val jsonString = json.encodeToString(event)
                    eventFile.writeText(jsonString)

                    Timber.d("Event queued: ${event.id}")
                    true
                } catch (e: Exception) {
                    Timber.e(e, "Failed to queue event")
                    false
                }
            }
        }

    /**
     * Add a simple training event.
     */
    suspend fun addTrainingEvent(
        type: String,
        metrics: Map<String, Double>? = null,
        metadata: Map<String, String>? = null,
    ): Boolean {
        val event =
            QueuedEvent(
                id = UUID.randomUUID().toString(),
                type = type,
                timestamp = System.currentTimeMillis(),
                metrics = metrics,
                metadata = metadata,
            )
        return addEvent(event)
    }

    /**
     * Get all pending events.
     */
    suspend fun getPendingEvents(): List<QueuedEvent> =
        withContext(ioDispatcher) {
            mutex.withLock {
                try {
                    queueDir
                        .listFiles()
                        ?.filter { it.extension == "json" }
                        ?.mapNotNull { file ->
                            try {
                                json.decodeFromString<QueuedEvent>(file.readText())
                            } catch (e: Exception) {
                                Timber.w(e, "Failed to parse event file: ${file.name}")
                                null
                            }
                        }?.sortedBy { it.timestamp }
                        ?: emptyList()
                } catch (e: Exception) {
                    Timber.e(e, "Failed to read event queue")
                    emptyList()
                }
            }
        }

    /**
     * Remove an event from the queue.
     */
    suspend fun removeEvent(eventId: String): Boolean =
        withContext(ioDispatcher) {
            mutex.withLock {
                try {
                    val eventFile = File(queueDir, "$eventId.json")
                    val deleted = eventFile.delete()
                    if (deleted) {
                        Timber.d("Event removed: $eventId")
                    }
                    deleted
                } catch (e: Exception) {
                    Timber.e(e, "Failed to remove event $eventId")
                    false
                }
            }
        }

    /**
     * Get queue size.
     */
    suspend fun getQueueSize(): Int =
        withContext(ioDispatcher) {
            mutex.withLock {
                queueDir.listFiles()?.count { it.extension == "json" } ?: 0
            }
        }

    /**
     * Clear all events from the queue.
     */
    suspend fun clear() =
        withContext(ioDispatcher) {
            mutex.withLock {
                queueDir.listFiles()?.forEach { file ->
                    if (!file.delete()) {
                        Timber.w("Failed to delete event file: ${file.name}")
                    }
                }
                Timber.d("Event queue cleared")
            }
        }
}

/**
 * An event waiting to be synced.
 */
@Serializable
data class QueuedEvent(
    /** Unique event ID */
    val id: String,
    /** Event type (e.g., "inference", "training_started", "training_completed") */
    val type: String,
    /** Event timestamp */
    val timestamp: Long,
    /** Numeric metrics */
    val metrics: Map<String, Double>? = null,
    /** String metadata */
    val metadata: Map<String, String>? = null,
)

/**
 * Common event types.
 */
object EventTypes {
    const val MODEL_LOADED = "model_loaded"
    const val MODEL_DOWNLOAD_STARTED = "model_download_started"
    const val MODEL_DOWNLOAD_COMPLETED = "model_download_completed"
    const val MODEL_DOWNLOAD_FAILED = "model_download_failed"
    const val INFERENCE_STARTED = "inference_started"
    const val INFERENCE_COMPLETED = "inference_completed"
    const val INFERENCE_FAILED = "inference_failed"
    const val TRAINING_STARTED = "training_started"
    const val TRAINING_COMPLETED = "training_completed"
    const val TRAINING_FAILED = "training_failed"
    const val DEVICE_REGISTERED = "device_registered"
    const val SYNC_STARTED = "sync_started"
    const val SYNC_COMPLETED = "sync_completed"
    const val SYNC_FAILED = "sync_failed"

    // Streaming generation events
    const val GENERATION_STARTED = "generation_started"
    const val CHUNK_PRODUCED = "chunk_produced"
    const val GENERATION_COMPLETED = "generation_completed"
    const val GENERATION_FAILED = "generation_failed"
}
