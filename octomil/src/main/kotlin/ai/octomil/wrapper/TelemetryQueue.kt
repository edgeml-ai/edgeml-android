package ai.octomil.wrapper

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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ai.octomil.BuildConfig
import ai.octomil.api.dto.TelemetryV2BatchRequest
import ai.octomil.api.dto.TelemetryV2Event
import ai.octomil.api.dto.TelemetryV2Resource
import timber.log.Timber
import java.io.Closeable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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
    private val orgId: String? = null,
    private val deviceId: String? = null,
) : Closeable {
    private val queue = ConcurrentLinkedQueue<InferenceTelemetryEvent>()
    private val flushMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var flushJob: Job? = null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    companion object {
        private const val TAG = "TelemetryQueue"
        private const val PERSIST_DIR_NAME = "octomil_telemetry"
        private const val MAX_PERSISTED_FILES = 100

        /**
         * Shared instance used for funnel event reporting from classes that don't hold
         * a direct TelemetryQueue reference (PairingManager, ModelManager, BenchmarkRunner).
         * Set automatically when the first TelemetryQueue is started with a sender.
         */
        @Volatile
        var shared: TelemetryQueue? = null
            private set
    }

    /**
     * Start the periodic flush timer. Call once after construction.
     */
    fun start() {
        if (flushIntervalMs > 0) {
            flushJob = scope.launch {
                while (isActive) {
                    delay(flushIntervalMs)
                    flush()
                }
            }
        }
        // Attempt to load and resend any persisted events from a previous session
        scope.launch { loadPersistedEvents() }

        if (sender != null && shared == null) {
            shared = this
        }
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
                    val batch = buildV2Batch(events)
                    sender.sendBatch(batch)
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
     * Build a v2 OTLP batch request from a list of inference telemetry events.
     */
    internal fun buildV2Batch(events: List<InferenceTelemetryEvent>): TelemetryV2BatchRequest {
        val resource = TelemetryV2Resource(
            sdk = "android",
            sdkVersion = BuildConfig.OCTOMIL_VERSION,
            deviceId = deviceId,
            platform = "android",
            orgId = orgId,
        )

        val v2Events = events.map { event ->
            val attrs = mutableMapOf(
                "model.id" to event.modelId,
                "inference.duration_ms" to event.latencyMs.toString(),
                "inference.modality" to "on_device",
                "device.compute_unit" to "cpu",
                "model.format" to "tflite",
                "inference.success" to event.success.toString(),
            )
            if (event.errorMessage != null) {
                attrs["error.message"] = event.errorMessage
            }

            TelemetryV2Event(
                name = "inference.completed",
                timestamp = isoFormatter.format(Date(event.timestampMs)),
                attributes = attrs,
            )
        }

        return TelemetryV2BatchRequest(resource = resource, events = v2Events)
    }

    /**
     * Build a v2 OTLP batch request for a single funnel event.
     */
    internal fun buildV2FunnelBatch(event: FunnelEvent): TelemetryV2BatchRequest {
        val resource = TelemetryV2Resource(
            sdk = "android",
            sdkVersion = event.sdkVersion ?: BuildConfig.OCTOMIL_VERSION,
            deviceId = event.deviceId ?: deviceId,
            platform = "android",
            orgId = orgId,
        )

        val attrs = mutableMapOf<String, String>()
        attrs["funnel.success"] = event.success.toString()
        attrs["funnel.source"] = event.source
        event.modelId?.let { attrs["model.id"] = it }
        event.rolloutId?.let { attrs["funnel.rollout_id"] = it }
        event.sessionId?.let { attrs["funnel.session_id"] = it }
        event.failureReason?.let { attrs["error.message"] = it }
        event.failureCategory?.let { attrs["error.category"] = it }
        event.durationMs?.let { attrs["funnel.duration_ms"] = it.toString() }
        event.metadata?.forEach { (k, v) -> attrs["funnel.metadata.$k"] = v }

        val v2Event = TelemetryV2Event(
            name = "funnel.${event.stage}",
            timestamp = isoFormatter.format(Date()),
            attributes = attrs,
        )

        return TelemetryV2BatchRequest(resource = resource, events = listOf(v2Event))
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
                val batch = buildV2Batch(events)
                sender.sendBatch(batch)
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
        if (shared === this) {
            shared = null
        }
    }

    /**
     * Report a funnel analytics event. Fire-and-forget via coroutine scope.
     */
    fun reportFunnelEvent(
        stage: String,
        success: Boolean = true,
        deviceId: String? = null,
        modelId: String? = null,
        rolloutId: String? = null,
        sessionId: String? = null,
        failureReason: String? = null,
        failureCategory: String? = null,
        durationMs: Int? = null,
        platform: String? = "android",
        metadata: Map<String, String>? = null,
    ) {
        scope.launch {
            try {
                val event = FunnelEvent(
                    stage = stage,
                    success = success,
                    deviceId = deviceId,
                    modelId = modelId,
                    rolloutId = rolloutId,
                    sessionId = sessionId,
                    failureReason = failureReason,
                    failureCategory = failureCategory,
                    durationMs = durationMs,
                    sdkVersion = BuildConfig.OCTOMIL_VERSION,
                    platform = platform,
                    metadata = metadata,
                )
                val batch = buildV2FunnelBatch(event)
                sender?.sendBatch(batch)
            } catch (e: Exception) {
                Timber.d(e, "Funnel event reporting failed")
            }
        }
    }
}

/**
 * A single funnel analytics event.
 */
@Serializable
data class FunnelEvent(
    val stage: String,
    val success: Boolean = true,
    val source: String = "sdk_android",
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("model_id") val modelId: String? = null,
    @SerialName("rollout_id") val rolloutId: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("failure_reason") val failureReason: String? = null,
    @SerialName("failure_category") val failureCategory: String? = null,
    @SerialName("duration_ms") val durationMs: Int? = null,
    @SerialName("sdk_version") val sdkVersion: String? = null,
    val platform: String? = "android",
    val metadata: Map<String, String>? = null,
)

/**
 * Abstraction for sending telemetry to the server via the v2 OTLP envelope.
 *
 * Implementations should throw on failure so the queue can fall back to
 * disk persistence.
 */
fun interface TelemetrySender {
    /**
     * Send a v2 OTLP telemetry batch to POST /api/v2/telemetry/events.
     *
     * @throws Exception if the send fails (events will be persisted to disk).
     */
    suspend fun sendBatch(batch: TelemetryV2BatchRequest)
}
