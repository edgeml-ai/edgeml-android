package ai.edgeml.wrapper

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A single inference telemetry event.
 */
@Serializable
data class InferenceTelemetryEvent(
    /** Model identifier. */
    val modelId: String,
    /** Inference latency in milliseconds. */
    val latencyMs: Double,
    /** Unix timestamp in milliseconds when inference started. */
    val timestampMs: Long,
    /** Whether the inference completed successfully. */
    val success: Boolean,
    /** Error message if inference failed. Null on success. */
    val errorMessage: String? = null,
)

/**
 * Thread-safe queue that batches inference telemetry events and flushes them
 * to the server (or persists to disk on failure).
 *
 * Flush triggers:
 * 1. Timer-based: every [flushIntervalMs] milliseconds.
 * 2. Size-based: when the queue reaches [batchSize] events.
 *
 * Events that fail to send are persisted to [persistDir] as JSON files and
 * retried on the next flush cycle.
 *
 * Thread safety: uses [ConcurrentLinkedQueue] for lock-free enqueue, and a
 * [Mutex] around flush/persist to avoid concurrent disk or network writes.
 */
class TelemetryQueue internal constructor(
    private val batchSize: Int,
    private val flushIntervalMs: Long,
    private val persistDir: File?,
    private val sender: TelemetrySender?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Closeable {
    private val queue = ConcurrentLinkedQueue<InferenceTelemetryEvent>()
    private val flushMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var flushJob: Job? = null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    companion object {
        private const val TAG = "TelemetryQueue"
        private const val PERSIST_DIR_NAME = "edgeml_telemetry"
        private const val MAX_PERSISTED_FILES = 100
    }

    /**
     * Start the periodic flush timer. Call once after construction.
     */
    fun start() {
        if (flushIntervalMs <= 0) return
        flushJob = scope.launch {
            while (isActive) {
                delay(flushIntervalMs)
                flush()
            }
        }
        // Attempt to load and resend any persisted events from a previous session
        scope.launch { loadPersistedEvents() }
    }

    /**
     * Enqueue a telemetry event. Non-blocking. If the queue reaches [batchSize],
     * a flush is triggered asynchronously.
     */
    fun enqueue(event: InferenceTelemetryEvent) {
        queue.add(event)
        if (queue.size >= batchSize) {
            scope.launch { flush() }
        }
    }

    /**
     * The number of events currently buffered (not yet flushed).
     */
    val pendingCount: Int get() = queue.size

    /**
     * Flush all buffered events. Sends to the server if a [TelemetrySender] is
     * configured; otherwise persists to disk.
     */
    suspend fun flush() {
        flushMutex.withLock {
            val events = drainQueue()
            if (events.isEmpty()) return

            Timber.d("Flushing %d telemetry events", events.size)

            if (sender != null) {
                try {
                    sender.send(events)
                    Timber.d("Telemetry flush succeeded: %d events", events.size)
                } catch (e: Exception) {
                    Timber.w(e, "Telemetry flush failed, persisting %d events to disk", events.size)
                    persistEvents(events)
                }
            } else {
                // No sender configured -- persist locally for later retrieval
                persistEvents(events)
            }
        }
    }

    /**
     * Drain all events from the concurrent queue into a list.
     */
    private fun drainQueue(): List<InferenceTelemetryEvent> {
        val events = mutableListOf<InferenceTelemetryEvent>()
        while (true) {
            val event = queue.poll() ?: break
            events.add(event)
        }
        return events
    }

    /**
     * Persist events to disk so they survive process restarts.
     */
    private fun persistEvents(events: List<InferenceTelemetryEvent>) {
        val dir = persistDir ?: return
        try {
            dir.mkdirs()
            // Enforce maximum persisted file count to prevent unbounded disk usage
            val existing = dir.listFiles()?.size ?: 0
            if (existing >= MAX_PERSISTED_FILES) {
                val oldest = dir.listFiles()?.minByOrNull { it.lastModified() }
                oldest?.delete()
            }
            val fileName = "telemetry_${System.currentTimeMillis()}.json"
            val file = File(dir, fileName)
            file.writeText(json.encodeToString(events))
            Timber.d("Persisted %d events to %s", events.size, file.name)
        } catch (e: Exception) {
            Timber.e(e, "Failed to persist telemetry events")
        }
    }

    /**
     * Load previously persisted events and attempt to send them.
     */
    private suspend fun loadPersistedEvents() {
        val dir = persistDir ?: return
        if (!dir.exists()) return
        val sender = this.sender ?: return

        val files = dir.listFiles()?.filter { it.extension == "json" }?.sortedBy { it.lastModified() }
            ?: return

        for (file in files) {
            try {
                val events: List<InferenceTelemetryEvent> = json.decodeFromString(file.readText())
                sender.send(events)
                file.delete()
                Timber.d("Resent %d persisted events from %s", events.size, file.name)
            } catch (e: Exception) {
                Timber.w(e, "Failed to resend persisted events from %s", file.name)
                // Leave the file for the next cycle
                break
            }
        }
    }

    /**
     * Stop the flush timer and flush remaining events.
     */
    override fun close() {
        flushJob?.cancel()
        // Best-effort synchronous drain: persist any remaining events
        val remaining = drainQueue()
        if (remaining.isNotEmpty()) {
            persistEvents(remaining)
        }
        scope.cancel()
    }
}

/**
 * Abstraction for sending telemetry batches to the server.
 *
 * Implementations should throw on failure so the queue can fall back to
 * disk persistence.
 */
fun interface TelemetrySender {
    /**
     * Send a batch of telemetry events.
     *
     * @throws Exception if the send fails (events will be persisted to disk).
     */
    suspend fun send(events: List<InferenceTelemetryEvent>)
}
